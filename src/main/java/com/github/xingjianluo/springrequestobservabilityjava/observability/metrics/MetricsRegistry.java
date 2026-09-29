package com.github.xingjianluo.springrequestobservabilityjava.observability.metrics;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 接口维度指标注册表。
 *
 * <p>线程安全设计：
 * <ul>
 *   <li>{@link ConcurrentHashMap} 管理接口 → 计数器，{@code computeIfAbsent} 保证每个接口只有一份计数器；</li>
 *   <li>所有计数使用 {@link LongAdder}，高并发下采用分段累加，计数不会丢失；</li>
 *   <li>每个 HTTP 请求在过滤器 {@code finally} 中恰好调用一次 {@link #record}，天然不重复；</li>
 *   <li>不提供 reset 操作，计数只增不减，开关/采样变更后各时间区间的口径连续可解释。</li>
 * </ul>
 */
@Component
public class MetricsRegistry {

    /**
     * 耗时桶上界（毫秒），固定不随运行时变化；最后一个为 +Inf。
     * 固定桶边界保证长时间运行后不同时间段的分布可以直接对比。
     */
    static final long[] BUCKET_UPPER_BOUNDS_MS = {10L, 50L, 100L, 200L, 500L, 1000L, 3000L, Long.MAX_VALUE};

    private final Map<String, EndpointCounters> endpoints = new ConcurrentHashMap<>();

    /**
     * 记录一次请求。
     *
     * @param endpoint     接口维度键（METHOD + URI 模板）
     * @param category     成功/参数错误/业务拒绝/系统异常
     * @param durationNanos 请求总耗时（纳秒）
     * @param sampledIn    该请求是否命中采样
     */
    public void record(String endpoint, ErrorCategory category, long durationNanos, boolean sampledIn) {
        EndpointCounters counters = endpoints.computeIfAbsent(endpoint, k -> new EndpointCounters());
        counters.record(category, durationNanos, sampledIn);
    }

    /**
     * 输出全部接口的只读快照，按接口名排序便于对比。
     */
    public List<EndpointMetricsSnapshot> snapshot() {
        return endpoints.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getValue().snapshot(e.getKey()))
                .toList();
    }

    private static final class EndpointCounters {
        private final LongAdder observedTotal = new LongAdder();
        private final LongAdder sampledTotal = new LongAdder();
        private final LongAdder success = new LongAdder();
        private final LongAdder paramError = new LongAdder();
        private final LongAdder bizReject = new LongAdder();
        private final LongAdder systemError = new LongAdder();
        private final LongAdder[] buckets = createBuckets();

        private static LongAdder[] createBuckets() {
            LongAdder[] array = new LongAdder[BUCKET_UPPER_BOUNDS_MS.length];
            for (int i = 0; i < array.length; i++) {
                array[i] = new LongAdder();
            }
            return array;
        }

        void record(ErrorCategory category, long durationNanos, boolean sampledIn) {
            // 观测总数：全量、无条件 +1（每次请求恰好一次）
            observedTotal.increment();
            if (!sampledIn) {
                return;
            }
            sampledTotal.increment();
            long durationMs = Math.max(0L, (durationNanos + 999_999L) / 1_000_000L);
            for (int i = 0; i < BUCKET_UPPER_BOUNDS_MS.length; i++) {
                if (durationMs <= BUCKET_UPPER_BOUNDS_MS[i]) {
                    buckets[i].increment();
                    break;
                }
            }
            switch (category) {
                case SUCCESS -> success.increment();
                case PARAM_ERROR -> paramError.increment();
                case BIZ_REJECT -> bizReject.increment();
                case SYSTEM_ERROR -> systemError.increment();
            }
        }

        EndpointMetricsSnapshot snapshot(String endpoint) {
            List<Long> bucketCounts = java.util.Arrays.stream(buckets).map(LongAdder::sum).toList();
            List<String> bounds = java.util.Arrays.stream(BUCKET_UPPER_BOUNDS_MS)
                    .mapToObj(b -> b == Long.MAX_VALUE ? "+Inf" : String.valueOf(b))
                    .toList();
            return new EndpointMetricsSnapshot(
                    endpoint,
                    observedTotal.sum(),
                    bucketCounts,
                    bounds,
                    success.sum(),
                    paramError.sum(),
                    bizReject.sum(),
                    systemError.sum(),
                    sampledTotal.sum());
        }
    }
}
