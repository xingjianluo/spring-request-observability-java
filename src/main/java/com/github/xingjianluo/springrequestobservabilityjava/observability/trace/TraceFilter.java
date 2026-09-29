package com.github.xingjianluo.springrequestobservabilityjava.observability.trace;

import com.github.xingjianluo.springrequestobservabilityjava.observability.metrics.EndpointMetricsRegistry;
import com.github.xingjianluo.springrequestobservabilityjava.observability.metrics.ErrorCategory;
import com.github.xingjianluo.springrequestobservabilityjava.observability.metrics.Outcome;
import com.github.xingjianluo.springrequestobservabilityjava.observability.web.GlobalExceptionHandler;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;

/**
 * 请求链路与指标的统一入口过滤器。
 *
 * <p>进入时（且仅在 REQUEST dispatch 执行一次，ASYNC dispatch 自动跳过）：
 * <ol>
 *     <li>生成或沿用调用方传入的合法 traceId，写入响应头 {@code X-Trace-Id}、
 *         请求属性、{@link TraceContext}（ThreadLocal + MDC），后续所有日志自动带上；</li>
 *     <li>记录起始时间，<b>一次性</b>决定“是否记账/是否采样”并存入请求属性，
 *         保证请求中途配置变化不影响本请求，且全程统计口径一致；</li>
 *     <li>全局 total 计数无条件 +1（即使关闭指标开关也记录流量总数）。</li>
 * </ol>
 *
 * <p>退出时：
 * <ul>
 *     <li>同步请求：直接记录该接口的结果与耗时；</li>
 *     <li>异步请求（controller 返回 {@code CompletableFuture} 等）：注册
 *         {@link AsyncListener}，在异步真正完成/超时/出错时记账，因此耗时覆盖完整异步过程，
 *         且同样“恰好一次”；</li>
 *     <li>无论是否抛异常，finally 中清理 ThreadLocal/MDC，线程池复用线程不残留标识。</li>
 * </ul>
 */
public class TraceFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_ATTRIBUTE = TraceFilter.class.getName() + ".traceId";
    public static final String START_NANOS_ATTRIBUTE = TraceFilter.class.getName() + ".startNanos";
    public static final String SAMPLED_ATTRIBUTE = TraceFilter.class.getName() + ".sampled";
    public static final String RECORD_ATTRIBUTE = TraceFilter.class.getName() + ".record";
    public static final String RECORDED_ATTRIBUTE = TraceFilter.class.getName() + ".recorded";

    private static final Logger log = LoggerFactory.getLogger(TraceFilter.class);

    private final EndpointMetricsRegistry registry;

    public TraceFilter(EndpointMetricsRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // 1) traceId：优先沿用调用方合法标识，否则新生成。
        String incoming = request.getHeader(TraceContext.TRACE_ID_HEADER);
        String traceId = TraceContext.isValidIncomingId(incoming) ? incoming : TraceContext.generateTraceId();

        TraceContext.setTraceId(traceId);
        request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
        // 尽早设置响应头：即便后续发生异常，调用方也能拿到标识。
        response.setHeader(TraceContext.TRACE_ID_HEADER, traceId);

        long startNanos = System.nanoTime();
        request.setAttribute(START_NANOS_ATTRIBUTE, startNanos);

        // 2) 记账/采样在入口一次性决定，保证全程口径一致、可解释。
        boolean record = registry.shouldRecord();
        boolean sampled = record && registry.isSampled();
        request.setAttribute(RECORD_ATTRIBUTE, record);
        request.setAttribute(SAMPLED_ATTRIBUTE, sampled);

        // 流量总数无条件计数；采样数在采样生效时计数。
        registry.incrementTotal();
        if (sampled) {
            registry.incrementSampled();
        }

        log.info("request received: {} {} traceId={} sampled={}",
                request.getMethod(), request.getRequestURI(), traceId, sampled);

        try {
            filterChain.doFilter(request, response);
        } finally {
            if (request.isAsyncStarted()) {
                // 异步：记账推迟到异步完成，当前 dispatch 的线程上下文先清理。
                request.getAsyncContext().addListener(new AsyncFinalizer());
                TraceContext.clear();
            } else {
                try {
                    finalizeMetrics(request, response);
                } finally {
                    TraceContext.clear();
                }
            }
        }
    }

    /** 同步出口与异步完成共用的记账逻辑，用 recorded 标志兜底，确保最多一次。 */
    private void finalizeMetrics(HttpServletRequest request, HttpServletResponse response) {
        if (Boolean.TRUE.equals(request.getAttribute(RECORDED_ATTRIBUTE))) {
            return;
        }
        request.setAttribute(RECORDED_ATTRIBUTE, Boolean.TRUE);

        boolean record = Boolean.TRUE.equals(request.getAttribute(RECORD_ATTRIBUTE));
        boolean sampled = Boolean.TRUE.equals(request.getAttribute(SAMPLED_ATTRIBUTE));
        String traceId = (String) request.getAttribute(TRACE_ID_ATTRIBUTE);

        // 异步完成回调运行在容器的 ASYNC dispatch 线程上，未经本过滤器设置过 MDC；
        // 这里依据请求属性补回上下文，保证“request completed”日志方括号中也能渲染 traceId。
        boolean contextRestored = false;
        if (traceId != null && TraceContext.getTraceId() == null) {
            TraceContext.setTraceId(traceId);
            contextRestored = true;
        }
        try {
            long startNanos = (Long) request.getAttribute(START_NANOS_ATTRIBUTE);
            long durationNanos = System.nanoTime() - startNanos;
            int status = response.getStatus();

            Outcome outcome = outcomeOf(status);
            ErrorCategory category = resolveCategory(request, status);

            if (record) {
                registry.record(resolveEndpoint(request), sampled, durationNanos, outcome, category);
            }

            log.info("request completed: {} {} status={} outcome={} category={} durationNanos={} traceId={}",
                    request.getMethod(), request.getRequestURI(), status, outcome, category, durationNanos, traceId);
        } finally {
            // 只清理本次临时补回的上下文；同步路径上的上下文由 doFilterInternal 的 finally 统一清理。
            if (contextRestored) {
                TraceContext.clear();
            }
        }
    }

    /**
     * 解析接口名：命中 controller 时用 URI 模板（@PathVariable 的具体值被归一化，
     * 避免高基数标签），未命中（如 404）归入 UNMAPPED。
     */
    private static String resolveEndpoint(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern instanceof String s && !s.isBlank()) {
            return request.getMethod() + " " + s;
        }
        return "UNMAPPED " + request.getMethod() + " " + request.getRequestURI();
    }

    /** 优先使用异常处理器写入的精确分类，否则按 HTTP 状态兜底分类。 */
    private static ErrorCategory resolveCategory(HttpServletRequest request, int status) {
        Object explicit = request.getAttribute(GlobalExceptionHandler.ERROR_CATEGORY_ATTRIBUTE);
        if (explicit instanceof ErrorCategory c && c != ErrorCategory.NONE) {
            return c;
        }
        return categoryOf(status);
    }

    private static Outcome outcomeOf(int status) {
        if (status >= 200 && status < 300) {
            return Outcome.SUCCESS;
        }
        if (status == 422) {
            return Outcome.BUSINESS_REJECTION;
        }
        if (status >= 400 && status < 500) {
            return Outcome.CLIENT_ERROR;
        }
        return Outcome.SERVER_ERROR;
    }

    private static ErrorCategory categoryOf(int status) {
        if (status >= 200 && status < 300) {
            return ErrorCategory.NONE;
        }
        if (status == 422) {
            return ErrorCategory.BUSINESS_REJECTION;
        }
        if (status >= 400 && status < 500) {
            return ErrorCategory.PARAM_ERROR;
        }
        return ErrorCategory.SYSTEM_ERROR;
    }

    /** 异步请求在真正结束（complete/timeout/error）时记账，三类事件都由 recorded 标志保证最多一次。 */
    private final class AsyncFinalizer implements AsyncListener {

        @Override
        public void onComplete(AsyncEvent event) {
            try {
                finalizeMetrics((HttpServletRequest) event.getSuppliedRequest(),
                        (HttpServletResponse) event.getSuppliedResponse());
            } finally {
                TraceContext.clear();
            }
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            HttpServletResponse response = (HttpServletResponse) event.getSuppliedResponse();
            if (!response.isCommitted()) {
                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            }
            try {
                finalizeMetrics((HttpServletRequest) event.getSuppliedRequest(), response);
            } finally {
                TraceContext.clear();
            }
        }

        @Override
        public void onError(AsyncEvent event) {
            // 异步链路上抛出的异常：记账后重新抛出交给容器/异常处理机制，避免统计残缺。
            try {
                finalizeMetrics((HttpServletRequest) event.getSuppliedRequest(),
                        (HttpServletResponse) event.getSuppliedResponse());
            } finally {
                TraceContext.clear();
            }
            Throwable thrown = event.getThrowable();
            if (thrown instanceof RuntimeException re) {
                throw re;
            }
            if (thrown instanceof Error err) {
                throw err;
            }
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            // no-op
        }
    }
}
