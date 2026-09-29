package com.github.xingjianluo.springrequestobservabilityjava.tests;

import com.github.xingjianluo.springrequestobservabilityjava.observability.config.ObservabilityProperties;
import com.github.xingjianluo.springrequestobservabilityjava.observability.metrics.EndpointMetricsSnapshot;
import com.github.xingjianluo.springrequestobservabilityjava.observability.metrics.ErrorCategory;
import com.github.xingjianluo.springrequestobservabilityjava.observability.metrics.MetricsRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指标口径单测：分类计数、耗时桶、高并发不丢不重、采样与开关语义。
 */
class MetricsTest {

    private static final Logger log = LoggerFactory.getLogger(MetricsTest.class);

    private EndpointMetricsSnapshot snapshotOf(MetricsRegistry registry, String endpoint) {
        List<EndpointMetricsSnapshot> snapshots = registry.snapshot();
        return snapshots.stream()
                .filter(s -> s.endpoint().equals(endpoint))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void countsAndBucketsAndCategoriesAreAccurate() {
        MetricsRegistry registry = new MetricsRegistry();
        String ep = "GET /api/demo/ping";

        registry.record(ep, ErrorCategory.SUCCESS, msToNanos(5), true);       // 桶 <=10
        registry.record(ep, ErrorCategory.SUCCESS, msToNanos(20), true);      // <=50
        registry.record(ep, ErrorCategory.PARAM_ERROR, msToNanos(150), true); // <=200
        registry.record(ep, ErrorCategory.BIZ_REJECT, msToNanos(600), true);  // <=1000
        registry.record(ep, ErrorCategory.SYSTEM_ERROR, msToNanos(4000), true);// +Inf

        EndpointMetricsSnapshot s = snapshotOf(registry, ep);
        assertEquals(5, s.observedTotal(), "判定依据: observedTotal 为全量计数");
        assertEquals(5, s.sampledTotal());
        assertEquals(2, s.successCount());
        assertEquals(1, s.paramErrorCount());
        assertEquals(1, s.bizRejectCount());
        assertEquals(1, s.systemErrorCount());
        assertEquals(List.of(1L, 1L, 0L, 1L, 0L, 1L, 0L, 1L), s.buckets(),
                "判定依据: 固定桶 [10,50,100,200,500,1000,3000,+Inf] 中 5ms/20ms/150ms/600ms/4000ms 各入其桶");
        assertEquals(List.of("10", "50", "100", "200", "500", "1000", "3000", "+Inf"),
                s.bucketUpperBounds());
        log.info("判定依据: 分类计数 success={}/param={}/biz={}/sys={}，耗时桶={}",
                s.successCount(), s.paramErrorCount(), s.bizRejectCount(), s.systemErrorCount(), s.buckets());
    }

    @Test
    void highConcurrencyDoesNotLoseOrDoubleCount() throws Exception {
        MetricsRegistry registry = new MetricsRegistry();
        String ep = "POST /api/demo/order";
        int threads = 32;
        int perThread = 5_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        registry.record(ep, ErrorCategory.SUCCESS, msToNanos(1), true);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS));
        pool.shutdown();

        EndpointMetricsSnapshot s = snapshotOf(registry, ep);
        long expected = (long) threads * perThread;
        assertEquals(expected, s.observedTotal(),
                "判定依据: LongAdder 分段累加，" + expected + " 次并发记录必须一次不丢");
        assertEquals(expected, s.successCount(),
                "判定依据: 每次请求恰好 record 一次，成功计数必须与总记录数相等（不重）");
        assertEquals(expected, s.buckets().stream().mapToLong(Long::longValue).sum(),
                "判定依据: 每条记录恰好落入一个桶，桶之和必须等于总数");
        log.info("判定依据: {} 线程 x {} 次 = {}，observedTotal={} success={} 桶和={}",
                threads, perThread, expected, s.observedTotal(), s.successCount(),
                s.buckets().stream().mapToLong(Long::longValue).sum());
    }

    @Test
    void samplingSemanticsAreExplainable() {
        MetricsRegistry registry = new MetricsRegistry();
        String ep = "GET /api/demo/ping";

        // 50 次采样命中（细项记录），50 次未命中（只增加 observedTotal）
        IntStream.range(0, 50).forEach(i -> registry.record(ep, ErrorCategory.SUCCESS, msToNanos(1), true));
        IntStream.range(0, 50).forEach(i -> registry.record(ep, ErrorCategory.SUCCESS, msToNanos(1), false));

        EndpointMetricsSnapshot s = snapshotOf(registry, ep);
        assertEquals(100, s.observedTotal(), "判定依据: 无论是否采样，观测总数始终全量 +1");
        assertEquals(50, s.sampledTotal(), "判定依据: 细项口径是「采样命中的请求」");
        assertEquals(50, s.successCount());
        log.info("判定依据: observedTotal={} 为全量分母，sampledTotal={} 为细项口径，开关/采样率变更不改写历史",
                s.observedTotal(), s.sampledTotal());
    }

    @Test
    void invalidSamplingRateIsClampedAndToggleIsReadable() {
        ObservabilityProperties props = new ObservabilityProperties();
        props.setSamplingRate(2.0d);
        assertEquals(1.0d, props.getSamplingRate(), "判定依据: 越界采样率被规整为 1");
        props.setSamplingRate(-0.5d);
        assertEquals(0.0d, props.getSamplingRate(), "判定依据: 负采样率被规整为 0");
        props.setSamplingRate(Double.NaN);
        assertEquals(1.0d, props.getSamplingRate(), "判定依据: NaN 被规整为 1，避免统计口径被污染");
        props.setMetricsEnabled(false);
        assertEquals(false, props.isMetricsEnabled(), "判定依据: 总开关可关闭，关闭期间过滤器跳过指标记录");
        log.info("判定依据: 采样率非法值均被规整到 [0,1]，开关默认 {}，可显式关闭",
                new ObservabilityProperties().isMetricsEnabled());
    }

    private static long msToNanos(long ms) {
        return ms * 1_000_000L;
    }
}
