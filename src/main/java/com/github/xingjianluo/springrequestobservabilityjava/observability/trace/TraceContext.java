package com.github.xingjianluo.springrequestobservabilityjava.observability.trace;

import org.slf4j.MDC;

/**
 * 链路标识的线程级持有器。
 *
 * <p>实现选择：使用普通 {@link ThreadLocal}（而非 InheritableThreadLocal），跨线程传播完全由
 * {@link Tracing} 显式做「快照 - 设置 - 清理」，避免线程池复用线程时残留上一个请求的标识。
 *
 * <p>同时写入 SLF4J {@link MDC}，使得同一请求处理过程中的所有日志都带 traceId。
 */
public final class TraceContext {
    public static final String TRACE_ID_MDC_KEY = "traceId";

    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    private TraceContext() {
    }

    /**
     * 在当前线程上设置链路标识并放入 MDC。
     */
    public static void setTraceId(String traceId) {
        TRACE_ID.set(traceId);
        if (traceId == null) {
            MDC.remove(TRACE_ID_MDC_KEY);
        } else {
            MDC.put(TRACE_ID_MDC_KEY, traceId);
        }
    }

    public static String getTraceId() {
        return TRACE_ID.get();
    }

    /**
     * 清理当前线程的链路标识与 MDC。请求结束 / 异步任务结束必须调用，防止线程复用串号。
     */
    public static void clear() {
        TRACE_ID.remove();
        MDC.remove(TRACE_ID_MDC_KEY);
    }
}
