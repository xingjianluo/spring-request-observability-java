package com.github.xingjianluo.springrequestobservabilityjava.observability.metrics;

import java.util.List;

/**
 * 单个接口的指标快照（只读视图）。
 */
public record EndpointMetricsSnapshot(
        String endpoint,
        long observedTotal,
        List<Long> buckets,
        List<String> bucketUpperBounds,
        long successCount,
        long paramErrorCount,
        long bizRejectCount,
        long systemErrorCount,
        long sampledTotal) {
}
