package com.github.xingjianluo.springrequestobservabilityjava.observability.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.springrequestobservabilityjava.observability.config.ObservabilityProperties;
import com.github.xingjianluo.springrequestobservabilityjava.observability.metrics.ErrorCategory;
import com.github.xingjianluo.springrequestobservabilityjava.observability.metrics.MetricsRegistry;
import com.github.xingjianluo.springrequestobservabilityjava.observability.trace.TraceContext;
import com.github.xingjianluo.springrequestobservabilityjava.observability.trace.TraceIdGenerator;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 最外层可观测性过滤器。
 *
 * <p>每个请求在进入时完成一次「入站 → 结束」闭环：
 * <ol>
 *   <li>读取上游 {@code X-Trace-Id}，合法则沿用，否则新生成；写入 {@link TraceContext} 与 MDC；
 *       并在响应头 {@code X-Trace-Id} 中回传调用方；</li>
 *   <li>进入时做一次采样判定，整个请求生命周期固定；</li>
 *   <li>请求结束（同步 dispatch 或异步 complete/error/timeout）时在唯一出口记录一次指标；</li>
 *   <li>任何漏到此处的未处理异常：记系统异常、返回脱敏 JSON，不影响链路标识回传与指标完整性；</li>
 *   <li>finally 中清理 ThreadLocal/MDC，保证 Tomcat 线程复用不残留。</li>
 * </ol>
 */
@Component
@Order(1)
public class TraceFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String ERROR_CATEGORY_ATTRIBUTE = TraceFilter.class.getName() + ".errorCategory";
    public static final String EXCEPTION_RESOLVED_ATTRIBUTE = TraceFilter.class.getName() + ".exceptionResolved";
    public static final String ASYNC_TRACE_ID_ATTRIBUTE = TraceFilter.class.getName() + ".asyncTraceId";
    public static final String ASYNC_SAMPLED_ATTRIBUTE = TraceFilter.class.getName() + ".asyncSampledIn";

    private static final Logger log = LoggerFactory.getLogger(TraceFilter.class);

    private final ObservabilityProperties properties;
    private final MetricsRegistry metricsRegistry;
    private final ObjectMapper objectMapper;

    public TraceFilter(ObservabilityProperties properties, MetricsRegistry metricsRegistry) {
        this.properties = properties;
        this.metricsRegistry = metricsRegistry;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 让过滤器参与 ASYNC 重派：异步结果在另一个 Tomcat 线程上回到过滤器链时，
     * 仍能恢复 traceId/MDC，使重派期间的日志也带同一标识。
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long startNanos = System.nanoTime();
        boolean asyncDispatch = request.getDispatcherType() == jakarta.servlet.DispatcherType.ASYNC;

        final String traceId;
        final boolean sampledIn;
        if (asyncDispatch) {
            // ASYNC 重派：复用首次 dispatch 存下的标识与采样判定，不重新生成、不重新采样
            Object saved = request.getAttribute(ASYNC_TRACE_ID_ATTRIBUTE);
            Object sampledAttr = request.getAttribute(ASYNC_SAMPLED_ATTRIBUTE);
            traceId = (saved instanceof String s) ? s : TraceIdGenerator.generate();
            sampledIn = !(sampledAttr instanceof Boolean b) || b;
            TraceContext.setTraceId(traceId);
            response.setHeader(TRACE_ID_HEADER, traceId);
            log.info("异步重派恢复链路: {} {} sampledIn={}", request.getMethod(), request.getRequestURI(), sampledIn);
            try {
                filterChain.doFilter(request, response);
            } finally {
                // 指标由首次 dispatch 注册的 AsyncListener 在 onComplete 统一记录，这里只清理上下文
                TraceContext.clear();
            }
            return;
        }

        String incoming = request.getHeader(TRACE_ID_HEADER);
        traceId = TraceIdGenerator.isValid(incoming) ? incoming : TraceIdGenerator.generate();
        TraceContext.setTraceId(traceId);
        // 提前写响应头：即使后续链路异常或异步完成，调用方也能拿到标识
        response.setHeader(TRACE_ID_HEADER, traceId);

        sampledIn = ThreadLocalRandom.current().nextDouble() < properties.getSamplingRate();

        log.info("收到请求: {} {} sampledIn={}", request.getMethod(), request.getRequestURI(), sampledIn);

        boolean asyncStarted = false;
        try {
            filterChain.doFilter(request, response);
        } catch (Exception ex) {
            // 正常情况下 Controller 抛出的异常已被 GlobalExceptionHandler 处理；
            // 这里是过滤器链下层抛出的、或未被处理的异常的最终安全网。
            boolean resolved = Boolean.TRUE.equals(request.getAttribute(EXCEPTION_RESOLVED_ATTRIBUTE));
            if (!resolved) {
                request.setAttribute(ERROR_CATEGORY_ATTRIBUTE, ErrorCategory.SYSTEM_ERROR);
                log.error("未处理异常穿透过滤器，按系统异常兜底", ex);
                writeSanitizedError(response, traceId);
            }
        } finally {
            asyncStarted = request.isAsyncStarted();
            if (asyncStarted) {
                // 异步：把标识与采样判定存到 request 上，供 ASYNC 重派线程恢复；
                // 指标等 AsyncListener 回调时再记；请求线程的上下文立即清理，
                // 异步业务线程使用提交时快照（见 Tracing.wrap / traceAwareExecutor）
                request.setAttribute(ASYNC_TRACE_ID_ATTRIBUTE, traceId);
                request.setAttribute(ASYNC_SAMPLED_ATTRIBUTE, sampledIn);
                String endpoint = resolveEndpoint(request);
                request.getAsyncContext().addListener(
                        new AsyncMetricsCollector(traceId, endpoint, startNanos, sampledIn, response),
                        request, response);
                TraceContext.clear();
            } else {
                try {
                    recordMetrics(request, response, startNanos, sampledIn);
                } finally {
                    TraceContext.clear();
                }
            }
        }
    }

    private void recordMetrics(HttpServletRequest request, HttpServletResponse response,
                               long startNanos, boolean sampledIn) {
        if (!properties.isMetricsEnabled()) {
            return;
        }
        String endpoint = resolveEndpoint(request);
        long durationNanos = System.nanoTime() - startNanos;
        ErrorCategory category = resolveCategory(request, response);
        metricsRegistry.record(endpoint, category, durationNanos, sampledIn);
        log.info("请求完成: endpoint={} category={} durationMs={} sampledIn={}",
                endpoint, category, (durationNanos + 999_999L) / 1_000_000L, sampledIn);
    }

    private void writeSanitizedError(HttpServletResponse response, String traceId) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ApiErrorResponse body = new ApiErrorResponse(traceId,
                ErrorCategory.SYSTEM_ERROR.name(), "服务内部错误，请稍后重试");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private static String resolveEndpoint(HttpServletRequest request) {
        Object template = request.getAttribute(EndpointPathInterceptor.ENDPOINT_TEMPLATE_ATTRIBUTE);
        String path = (template instanceof String s && !s.isBlank()) ? s : request.getRequestURI();
        return request.getMethod() + " " + path;
    }

    private static ErrorCategory resolveCategory(HttpServletRequest request, HttpServletResponse response) {
        Object explicit = request.getAttribute(ERROR_CATEGORY_ATTRIBUTE);
        if (explicit instanceof ErrorCategory category) {
            return category;
        }
        // 兜底：按 HTTP 状态归类（异步 dispatch 时异常处理器的属性可能不在同一 request 上）
        int status = response.getStatus();
        if (status >= 500) {
            return ErrorCategory.SYSTEM_ERROR;
        }
        if (status >= 400) {
            return status == HttpStatus.CONFLICT.value() ? ErrorCategory.BIZ_REJECT : ErrorCategory.PARAM_ERROR;
        }
        return ErrorCategory.SUCCESS;
    }

    /**
     * 异步请求指标收集器。Servlet 规范保证 complete 前会触发 error/timeout 中的至多若干回调，
     * 用 {@link AtomicBoolean} 保证一次异步请求恰好记录一次指标。
     */
    private final class AsyncMetricsCollector implements AsyncListener {
        private final String traceId;
        private final String endpoint;
        private final long startNanos;
        private final boolean sampledIn;
        private final HttpServletResponse response;
        private final AtomicBoolean recorded = new AtomicBoolean(false);

        private AsyncMetricsCollector(String traceId, String endpoint, long startNanos,
                                      boolean sampledIn, HttpServletResponse response) {
            this.traceId = traceId;
            this.endpoint = endpoint;
            this.startNanos = startNanos;
            this.sampledIn = sampledIn;
            this.response = response;
        }

        @Override
        public void onComplete(AsyncEvent event) {
            record("complete", event.getSuppliedResponse());
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            record("timeout", event.getSuppliedResponse());
        }

        @Override
        public void onError(AsyncEvent event) {
            record("error", event.getSuppliedResponse());
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            // no-op
        }

        private void record(String reason, jakarta.servlet.ServletResponse suppliedResponse) {
            if (!recorded.compareAndSet(false, true)) {
                return;
            }
            TraceContext.setTraceId(traceId);
            try {
                if (eventHasError(reason)) {
                    log.error("异步请求异常结束: endpoint={} reason={}", endpoint, reason);
                }
                if (properties.isMetricsEnabled()) {
                    long durationNanos = System.nanoTime() - startNanos;
                    HttpServletResponse resp = (suppliedResponse instanceof HttpServletResponse hr) ? hr : response;
                    ErrorCategory category = resolveCategory(resp);
                    metricsRegistry.record(endpoint, category, durationNanos, sampledIn);
                    log.info("异步请求完成: endpoint={} category={} reason={} durationMs={} sampledIn={}",
                            endpoint, category, reason,
                            (durationNanos + 999_999L) / 1_000_000L, sampledIn);
                }
            } finally {
                TraceContext.clear();
            }
        }

        private static boolean eventHasError(String reason) {
            return !"complete".equals(reason);
        }

        private static ErrorCategory resolveCategory(HttpServletResponse response) {
            int status = response.getStatus();
            if (status >= 500 || status == 0) {
                return ErrorCategory.SYSTEM_ERROR;
            }
            if (status >= 400) {
                return status == HttpStatus.CONFLICT.value() ? ErrorCategory.BIZ_REJECT : ErrorCategory.PARAM_ERROR;
            }
            return ErrorCategory.SUCCESS;
        }
    }
}
