package com.github.xingjianluo.springrequestobservabilityjava.observability.metrics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 单个接口（{@code "GET /api/demo/hello"} 这样的模板化路径）的指标集合。
 *
 * <p>计数口径（与采样率变化无关、始终可解释）：
 * <ul>
 *     <li>{@code total}：进入该接口的全部请求数（由 registry 统一累加）；</li>
 *     <li>{@code sampled}：按当时采样率被采入明细统计的请求数；</li>
 *     <li>成功 / 4xx / 业务拒绝 / 5xx 以及异常分类计数：对<b>所有</b>请求记录，
 *         因此任何时刻四类结果之和 == total，开关/采样变化不破坏成功失败统计的完整性；</li>
 *     <li>耗时直方图与 min/max/avg：仅对采样请求记录，{@code count == sampled}。
 *         采样率为 1.0（默认）时耗时分布即为全量口径。</li>
 * </ul>
 *
 * <p>所有计数使用 {@link LongAdder}（高并发下分段累加，不丢不重），
 * 最小/最大值使用 {@link AtomicLong} 的 CAS 更新。
 */
public class EndpointMetrics {

    /** 耗时直方图的桶上界（毫秒），最后一桶为 +Inf。 */
    static final long[] BUCKET_UPPER_BOUNDS_MS = {10, 50, 100, 200, 500, 1000};

    private static final long[] BUCKET_UPPER_BOUNDS_NS = new long[BUCKET_UPPER_BOUNDS_MS.length];

    static {
        for (int i = 0; i < BUCKET_UPPER_BOUNDS_MS.length; i++) {
            BUCKET_UPPER_BOUNDS_NS[i] = BUCKET_UPPER_BOUNDS_MS[i] * 1_000_000L;
        }
    }

    private final String endpoint;

    private final LongAdder success = new LongAdder();
    private final LongAdder clientError = new LongAdder();
    private final LongAdder businessRejection = new LongAdder();
    private final LongAdder serverError = new LongAdder();

    private final LongAdder paramErrors = new LongAdder();
    private final LongAdder businessErrors = new LongAdder();
    private final LongAdder systemErrors = new LongAdder();

    // ---- 以下为采样口径的耗时统计 ----
    private final LongAdder latencyCount = new LongAdder();
    private final LongAdder latencyTotalNanos = new LongAdder();
    private final LongAdder[] latencyBuckets = new LongAdder[BUCKET_UPPER_BOUNDS_NS.length + 1];
    private final AtomicLong minNanos = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxNanos = new AtomicLong(0);

    public EndpointMetrics(String endpoint) {
        this.endpoint = endpoint;
        for (int i = 0; i < latencyBuckets.length; i++) {
            latencyBuckets[i] = new LongAdder();
        }
    }

    public String getEndpoint() {
        return endpoint;
    }

    /**
     * 记录一次请求的结果。
     *
     * @param sampled       该请求是否被采入耗时样本（进入时一次性决定，中途不变）
     * @param durationNanos 请求处理耗时（纳秒）
     * @param outcome       请求结果大类
     * @param category      异常分类；无异常为 {@link ErrorCategory#NONE}
     */
    public void record(boolean sampled, long durationNanos, Outcome outcome, ErrorCategory category) {
        // 结果计数：所有请求都记，保证成功/失败口径始终完整。
        switch (outcome) {
            case SUCCESS -> success.increment();
            case CLIENT_ERROR -> clientError.increment();
            case BUSINESS_REJECTION -> businessRejection.increment();
            case SERVER_ERROR -> serverError.increment();
        }

        // 异常分类计数：同样对所有请求记录。
        switch (category) {
            case PARAM_ERROR -> paramErrors.increment();
            case BUSINESS_REJECTION -> businessErrors.increment();
            case SYSTEM_ERROR -> systemErrors.increment();
            case NONE -> { /* 无异常不计 */ }
        }

        // 耗时分布：只记录采样请求，样本之间统计口径一致。
        if (sampled) {
            latencyCount.increment();
            latencyTotalNanos.add(durationNanos);
            latencyBuckets[bucketIndex(durationNanos)].increment();
            updateMin(minNanos, durationNanos);
            updateMax(maxNanos, durationNanos);
        }
    }

    private static int bucketIndex(long durationNanos) {
        for (int i = 0; i < BUCKET_UPPER_BOUNDS_NS.length; i++) {
            if (durationNanos <= BUCKET_UPPER_BOUNDS_NS[i]) {
                return i;
            }
        }
        return BUCKET_UPPER_BOUNDS_NS.length;
    }

    private static void updateMin(AtomicLong holder, long value) {
        long current;
        while (value < (current = holder.get()) && !holder.compareAndSet(current, value)) {
            // CAS 失败说明有更小的值先写入，重读重试即可。
        }
    }

    private static void updateMax(AtomicLong holder, long value) {
        long current;
        while (value > (current = holder.get()) && !holder.compareAndSet(current, value)) {
            // CAS 失败说明有更大的值先写入，重读重试即可。
        }
    }

    /** 生成一致性快照（用于 /observability/metrics 输出）。 */
    public EndpointView snapshot() {
        long count = latencyCount.sum();
        long totalNanos = latencyTotalNanos.sum();
        long min = minNanos.get();
        long max = maxNanos.get();

        Map<String, Long> buckets = new LinkedHashMap<>();
        for (int i = 0; i < BUCKET_UPPER_BOUNDS_MS.length; i++) {
            buckets.put("<= " + BUCKET_UPPER_BOUNDS_MS[i] + "ms", latencyBuckets[i].sum());
        }
        buckets.put("+Inf", latencyBuckets[BUCKET_UPPER_BOUNDS_MS.length].sum());

        Map<String, Long> exceptions = new LinkedHashMap<>();
        exceptions.put("PARAM_ERROR", paramErrors.sum());
        exceptions.put("BUSINESS_REJECTION", businessErrors.sum());
        exceptions.put("SYSTEM_ERROR", systemErrors.sum());

        double avgNanos = count == 0 ? 0.0d : (double) totalNanos / count;
        return new EndpointView(
                endpoint,
                success.sum() + clientError.sum() + businessRejection.sum() + serverError.sum(),
                count,
                success.sum(),
                clientError.sum(),
                businessRejection.sum(),
                serverError.sum(),
                exceptions,
                count,
                count == 0 ? 0 : min,
                count == 0 ? 0 : max,
                avgNanos,
                buckets);
    }

    /** 清空该接口的全部计数（测试/运维重置口径时使用）。 */
    public void reset() {
        success.reset();
        clientError.reset();
        businessRejection.reset();
        serverError.reset();
        paramErrors.reset();
        businessErrors.reset();
        systemErrors.reset();
        latencyCount.reset();
        latencyTotalNanos.reset();
        for (LongAdder bucket : latencyBuckets) {
            bucket.reset();
        }
        minNanos.set(Long.MAX_VALUE);
        maxNanos.set(0);
    }
}
