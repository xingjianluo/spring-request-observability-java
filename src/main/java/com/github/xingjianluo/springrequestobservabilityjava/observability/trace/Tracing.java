package com.github.xingjianluo.springrequestobservabilityjava.observability.trace;

import java.util.concurrent.Callable;

/**
 * 跨线程链路上下文传播工具。
 *
 * <p>传播语义：在<strong>提交任务的线程</strong>上快照当前 traceId（通常是请求线程），
 * 任务在工作线程执行时设置该快照；任务结束后在 finally 中恢复工作线程原有的值，
 * 若原来没有则清理干净。因此：
 * <ul>
 *   <li>异步任务的日志、指标、异常都能看到同一个 traceId；</li>
 *   <li>线程池线程复用时不会残留上一个任务的标识。</li>
 * </ul>
 */
public final class Tracing {
    private Tracing() {
    }

    /**
     * 包装 Runnable。调用应发生在持有正确链路标识的请求线程上。
     */
    public static Runnable wrap(Runnable task) {
        String captured = TraceContext.getTraceId();
        return () -> runWith(captured, task);
    }

    /**
     * 包装 Callable。
     */
    public static <T> Callable<T> wrap(Callable<T> task) {
        String captured = TraceContext.getTraceId();
        return () -> callWith(captured, task);
    }

    private static void runWith(String captured, Runnable task) {
        String previous = TraceContext.getTraceId();
        TraceContext.setTraceId(captured);
        try {
            task.run();
        } finally {
            restore(previous);
        }
    }

    private static <T> T callWith(String captured, Callable<T> task) throws Exception {
        String previous = TraceContext.getTraceId();
        TraceContext.setTraceId(captured);
        try {
            return task.call();
        } finally {
            restore(previous);
        }
    }

    private static void restore(String previous) {
        if (previous == null) {
            TraceContext.clear();
        } else {
            TraceContext.setTraceId(previous);
        }
    }
}
