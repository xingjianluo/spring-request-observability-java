package com.github.xingjianluo.springrequestobservabilityjava.observability.metrics;

import java.util.Map;

public record MetricsSnapshot(
        boolean metricsEnabled,
        double sampleRate,
        long totalRequests,
        long sampledRequests,
        Map<String, EndpointView> endpoints) {
}
