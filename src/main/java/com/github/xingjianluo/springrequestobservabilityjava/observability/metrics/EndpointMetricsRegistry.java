package com.github.xingjianluo.springrequestobservabilityjava.observability.metrics;

import com.github.xingjianluo.springrequestobservabilityjava.observability.config.ObservabilityProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;

/**
 * 接口指标注册表：按模板化路径（{@code "GET /api/demo/hello"}）聚合各接口指标，
 * 并维护两个全局计数器。
 *
 * <p>全局计数器：
 * <ul>
 *     <li>{@code totalRequests}：所有进入的 HTTP 请求，无论开关/采样如何都 +1，
 *         它回答“一共来了多少请求”；</li>
 *     <li>{@code sampledRequests}：被采入耗时样本的请求数，它回答
 *         “耗时分布是基于多少请求算出来的”。</li>
 * </ul>
 *
 * <p>采样判定 {@link #isSampled()} 只在请求进入过滤器时调用一次，结果作为请求属性贯穿处理全程，
 * 因此配置中途变化不会让同一个请求“记一半”，每个请求恰好记录一次（不丢不重）。
 */
@Component
public class EndpointMetricsRegistry {

    private final ObservabilityProperties properties;
    private final ConcurrentHashMap<String, EndpointMetrics> endpoints = new ConcurrentHashMap<>();

    private final LongAdder totalRequests = new LongAdder();
    private final LongAdder sampledRequests = new LongAdder();

    public EndpointMetricsRegistry(ObservabilityProperties properties) {
        this.properties = properties;
    }

    /** 指标采集总开关是否开启。关闭后不再创建明细，但全局 total 仍由过滤器累加。 */
    public boolean shouldRecord() {
        return properties.isEnabled();
    }

    /**
     * 按当前采样率做一次伯努利判定（1.0 恒为 true，0.0 恒为 false）。
     * 使用 {@link ThreadLocalRandom} 保证高并发下无竞争、可线性扩展。
     */
    public boolean isSampled() {
        if (!properties.isEnabled()) {
            return false;
        }
        double rate = properties.getSampleRate();
        if (rate <= 0.0d) {
            return false;
        }
        if (rate >= 1.0d) {
            return true;
        }
        return ThreadLocalRandom.current().nextDouble() < rate;
    }

    /**
     * 记录一次请求。每个请求恰好调用一次：
     * 同步请求在过滤器出口调用；异步请求在 {@code onComplete} 中调用。
     */
    public void record(String endpoint, boolean sampled, long durationNanos,
                       Outcome outcome, ErrorCategory category) {
        if (endpoint == null) {
            endpoint = "UNMAPPED";
        }
        EndpointMetrics metrics = endpoints.computeIfAbsent(endpoint, EndpointMetrics::new);
        metrics.record(sampled, durationNanos, outcome, category);
    }

    /** 累加全局“全部请求”计数。 */
    public void incrementTotal() {
        totalRequests.increment();
    }

    /** 累加全局“采样请求”计数。 */
    public void incrementSampled() {
        sampledRequests.increment();
    }

    /** 生成当前时刻的一致性快照。各 {@code sum()} 之间不保证严格同一瞬间，但均为单调计数。 */
    public MetricsSnapshot snapshot() {
        Map<String, EndpointView> views = new TreeMap<>();
        endpoints.forEach((name, metrics) -> views.put(name, metrics.snapshot()));
        return new MetricsSnapshot(
                properties.isEnabled(),
                properties.getSampleRate(),
                totalRequests.sum(),
                sampledRequests.sum(),
                views);
    }

    /** 清零全部指标（全局 + 各接口）。供测试与运维重置基线使用。 */
    public void reset() {
        totalRequests.reset();
        sampledRequests.reset();
        endpoints.forEach((name, metrics) -> metrics.reset());
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public double getSampleRate() {
        return properties.getSampleRate();
    }

    /** 运行时调整采样率；只影响此后进入的新请求。 */
    public void setSampleRate(double sampleRate) {
        properties.setSampleRate(sampleRate);
    }
}
