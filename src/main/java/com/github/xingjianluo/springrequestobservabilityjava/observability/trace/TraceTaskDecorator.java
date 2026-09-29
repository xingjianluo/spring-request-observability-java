package com.github.xingjianluo.springrequestobservabilityjava.observability.trace;

import org.springframework.core.task.TaskDecorator;

/**
 * Spring {@link TaskDecorator} 扩展点：配置在 {@code ThreadPoolTaskExecutor} 上后，
 * 所有经 @Async / 该线程池执行的任务在提交时都会捕获请求线程的 traceId，
 * 并在执行线程上恢复，任务结束时清理，避免线程复用导致串号。
 */
public class TraceTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        return TraceContext.wrap(runnable);
    }
}
