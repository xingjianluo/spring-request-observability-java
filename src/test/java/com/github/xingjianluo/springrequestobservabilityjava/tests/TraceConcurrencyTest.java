package com.github.xingjianluo.springrequestobservabilityjava.tests;

import com.github.xingjianluo.springrequestobservabilityjava.observability.metrics.EndpointView;
import com.github.xingjianluo.springrequestobservabilityjava.observability.metrics.MetricsSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 高并发集成测试：少量 Tomcat 工作线程被大量并发请求复用，
 * 验证 traceId 不串号、不残留，以及接口计数不丢不重。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TraceConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(TraceConcurrencyTest.class);

    private static final int TOTAL_REQUESTS = 600;
    private static final int CLIENT_THREADS = 24;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void resetMetrics() {
        restTemplate.postForEntity("http://localhost:" + port + "/observability/metrics/reset", null, Map.class);
    }

    @Test
    void highConcurrencyKeepsTraceIdsDistinctAndCountsExact() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CLIENT_THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(TOTAL_REQUESTS);
        Set<String> traceIds = ConcurrentHashMap.newKeySet();
        Set<String> bodyTraceIds = ConcurrentHashMap.newKeySet();
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger httpOk = new AtomicInteger();

        for (int i = 0; i < TOTAL_REQUESTS; i++) {
            final int index = i;
            pool.execute(() -> {
                try {
                    start.await();
                    ResponseEntity<Map> resp = restTemplate.getForEntity(
                            "http://localhost:" + port + "/api/demo/hello?name=u" + index, Map.class);
                    if (resp.getStatusCode().is2xxSuccessful()) {
                        httpOk.incrementAndGet();
                    } else {
                        failures.incrementAndGet();
                    }
                    String headerTraceId = resp.getHeaders().getFirst("X-Trace-Id");
                    traceIds.add(headerTraceId);
                    bodyTraceIds.add(String.valueOf(resp.getBody().get("traceId")));
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "判定依据：所有并发请求都应在超时前完成");
        pool.shutdownNow();

        log.info("[高并发] 总请求={} 成功HTTP={} 失败={} 响应头traceId去重数={} 响应体traceId去重数={}",
                TOTAL_REQUESTS, httpOk.get(), failures.get(), traceIds.size(), bodyTraceIds.size());

        assertEquals(0, failures.get(), "判定依据：无请求级失败");
        assertEquals(TOTAL_REQUESTS, httpOk.get(), "判定依据：全部返回 2xx");
        assertEquals(TOTAL_REQUESTS, traceIds.size(), "判定依据：每个请求的响应头 traceId 全局唯一，无串号");
        assertEquals(TOTAL_REQUESTS, bodyTraceIds.size(), "判定依据：响应体内 traceId 同样全局唯一");
        assertEquals(traceIds, bodyTraceIds, "判定依据：响应头与响应体的标识集合完全一致");

        MetricsSnapshot snapshot = restTemplate.getForObject(
                "http://localhost:" + port + "/observability/metrics", MetricsSnapshot.class);
        EndpointView view = snapshot.endpoints().get("GET /api/demo/hello");
        log.info("[高并发-指标] total={} sampled={} success={} clientError={} serverError={} 桶合计={}",
                view.total(), view.sampled(), view.success(), view.clientError(), view.serverError(),
                view.latencyBuckets().values().stream().mapToLong(Long::longValue).sum());

        assertEquals(TOTAL_REQUESTS, view.total(), "判定依据：LongAdder 计数不丢不重，总数精确等于请求数");
        assertEquals(TOTAL_REQUESTS, view.success());
        assertEquals(0, view.clientError() + view.businessRejection() + view.serverError(),
                "判定依据：无失败计数");
        assertEquals(TOTAL_REQUESTS, view.sampled(), "判定依据：默认全量采样");
        assertEquals(TOTAL_REQUESTS, view.latencyBuckets().values().stream().mapToLong(Long::longValue).sum(),
                "判定依据：直方图各桶之和精确等于样本总数，无重复计数");
    }
}
