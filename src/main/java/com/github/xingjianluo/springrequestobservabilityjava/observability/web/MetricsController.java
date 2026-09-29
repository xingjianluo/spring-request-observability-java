package com.github.xingjianluo.springrequestobservabilityjava.observability.web;

import com.github.xingjianluo.springrequestobservabilityjava.observability.metrics.EndpointMetricsSnapshot;
import com.github.xingjianluo.springrequestobservabilityjava.observability.metrics.MetricsRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 本地指标查看端点。
 */
@RestController
@RequestMapping("/observability")
public class MetricsController {

    private final MetricsRegistry metricsRegistry;

    public MetricsController(MetricsRegistry metricsRegistry) {
        this.metricsRegistry = metricsRegistry;
    }

    @GetMapping("/metrics")
    public List<EndpointMetricsSnapshot> metrics() {
        return metricsRegistry.snapshot();
    }
}
