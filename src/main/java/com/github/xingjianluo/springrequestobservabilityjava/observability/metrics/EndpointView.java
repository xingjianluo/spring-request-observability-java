package com.github.xingjianluo.springrequestobservabilityjava.observability.metrics;

import java.util.Map;

public record EndpointView(
        String endpoint,
        long total,
        long sampled,
        long success,
        long clientError,
        long businessRejection,
        long serverError,
        Map<String, Long> exceptionsByCategory,
        long count,
        long minNanos,
        long maxNanos,
        double avgNanos,
        Map<String, Long> latencyBuckets) {
}
