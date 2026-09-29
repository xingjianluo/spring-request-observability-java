package com.github.xingjianluo.springrequestobservabilityjava.observability.config;

import com.github.xingjianluo.springrequestobservabilityjava.observability.trace.Tracing;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 异步执行器配置。
 *
 * <p>通过 {@link ThreadPoolTaskExecutor#setTaskDecorator TaskDecorator} 在任务提交时
 * （仍在请求线程上）用 {@link Tracing#wrap(Runnable)} 包装，使提交者的链路标识
 * 随任务进入工作线程，并在任务结束时清理，线程复用不残留。
 */
@Configuration
public class AsyncConfig {

    @Bean("traceAwareExecutor")
    public ThreadPoolTaskExecutor traceAwareExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("trace-async-");
        executor.setTaskDecorator(Tracing::wrap);
        executor.initialize();
        return executor;
    }
}
