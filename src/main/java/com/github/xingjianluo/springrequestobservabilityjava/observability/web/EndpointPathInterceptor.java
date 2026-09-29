package com.github.xingjianluo.springrequestobservabilityjava.observability.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 记录最匹配的 URI 模板（例如 {@code /api/demo/order/{id}}）作为指标接口维度。
 *
 * <p>放在拦截器而非过滤器中获取：此时 Spring MVC 已经完成路径匹配，
 * {@link HandlerMapping#BEST_MATCHING_PATTERN_ATTRIBUTE} 可用；
 * 这样同一模板的不同路径变量值会聚合成同一接口，不会产生高基数指标。
 */
@Component
public class EndpointPathInterceptor implements HandlerInterceptor {

    public static final String ENDPOINT_TEMPLATE_ATTRIBUTE =
            EndpointPathInterceptor.class.getName() + ".endpointTemplate";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern instanceof String template && !template.isBlank()) {
            request.setAttribute(ENDPOINT_TEMPLATE_ATTRIBUTE, template);
        }
        return true;
    }
}
