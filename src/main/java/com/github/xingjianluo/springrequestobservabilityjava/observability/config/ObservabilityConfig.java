package com.github.xingjianluo.springrequestobservabilityjava.observability.config;

import com.github.xingjianluo.springrequestobservabilityjava.observability.metrics.EndpointMetricsRegistry;
import com.github.xingjianluo.springrequestobservabilityjava.observability.trace.TraceFilter;
import com.github.xingjianluo.springrequestobservabilityjava.observability.trace.TraceTaskDecorator;
import org.springframework.boot.task.ThreadPoolTaskExecutorCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 可观测性装配：
 * <ol>
 *     <li>给 Spring Boot 自动配置的应用线程池（@Async 默认使用的 applicationTaskExecutor）
 *     增加 {@link TraceTaskDecorator}，让异步任务自动继承 traceId；</li>
 *     <li>注册 {@link TraceFilter} 并置于过滤器链最高优先级，确保任何环节
 *     （包括后续过滤器、Spring MVC、异常处理）都能看到 traceId。</li>
 * </ol>
 */
@Configuration
public class ObservabilityConfig {

    @Bean
    public ThreadPoolTaskExecutorCustomizer traceTaskExecutorCustomizer() {
        return taskExecutor -> taskExecutor.setTaskDecorator(new TraceTaskDecorator());
    }

    @Bean
    public FilterRegistrationBean<TraceFilter> traceFilterRegistration(EndpointMetricsRegistry registry) {
        FilterRegistrationBean<TraceFilter> registration = new FilterRegistrationBean<>(new TraceFilter(registry));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        registration.setName("traceFilter");
        return registration;
    }
}
