package com.github.xingjianluo.springrequestobservabilityjava.observability.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final EndpointPathInterceptor endpointPathInterceptor;

    public WebMvcConfig(EndpointPathInterceptor endpointPathInterceptor) {
        this.endpointPathInterceptor = endpointPathInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(endpointPathInterceptor).addPathPatterns("/**");
    }
}
