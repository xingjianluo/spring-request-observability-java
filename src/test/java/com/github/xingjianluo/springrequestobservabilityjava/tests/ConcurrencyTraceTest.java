package com.github.xingjianluo.springrequestobservabilityjava.tests;

import com.github.xingjianluo.springrequestobservabilityjava.observability.metrics.EndpointMetricsSnapshot;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 高并发 + Tomcat 线程复用场景的全栈测试。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConcurrencyTraceTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyTraceTest.class);
    private static final Set<String> EXPECTED_TRACE_IDS = new HashSet<>();

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void concurrentRequestsNeverShareTraceIdAndMetricsStayConsistent() throws Exception {
        int total = 80;
        ExecutorService clients = Executors.newFixedThreadPool(16);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < total; i++) {
                final int index = i;
                futures.add(clients.submit(() -> {
                    ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                            url("/api/demo/ping"), HttpMethod.GET, null,
                            new ParameterizedTypeReference<>() {
                            });
                    String headerId = resp.getHeaders().getFirst("X-Trace-Id");
                    String bodyId = String.valueOf(resp.getBody().get("traceId"));
                    assertNotNull(headerId, "判定依据: 每个响应都必须回传 X-Trace-Id");
                    assertEquals(headerId, bodyId,
                            "判定依据: 响应头与响应体的标识必须一致，请求=" + index);
                    synchronized (EXPECTED_TRACE_IDS) {
                        EXPECTED_TRACE_IDS.add(headerId);
                    }
                    log.info("判定依据: 请求#{} 完成，header traceId={} 与 body 一致", index, headerId);
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            clients.shutdown();
        }

        assertEquals(total, EXPECTED_TRACE_IDS.size(),
                "判定依据: " + total + " 个并发请求必须产生 " + total + " 个不同标识，线程复用也不串号");
        log.info("判定依据: {} 个并发请求去重后标识数 {}，Tomcat 线程复用未造成串号",
                total, EXPECTED_TRACE_IDS.size());

        EndpointMetricsSnapshot metrics = metricsOf("GET /api/demo/ping");
        assertTrue(metrics.observedTotal() >= total,
                "判定依据: ping 接口观测总数应包含本次全部请求，实际=" + metrics.observedTotal());
        assertTrue(metrics.successCount() >= total,
                "判定依据: 成功计数应包含本次全部请求，实际=" + metrics.successCount());
        assertEquals(metrics.successCount(),
                metrics.buckets().stream().mapToLong(Long::longValue).sum(),
                "判定依据: 采样命中的请求桶分布之和必须等于成功计数（每条只落一个桶）");
        log.info("判定依据: 指标聚合 observedTotal={} success={}，不同请求被聚合成同一接口维度 GET /api/demo/ping",
                metrics.observedTotal(), metrics.successCount());
    }

    @Test
    void incomingTraceIdIsHonouredWhenValid() {
        HttpHeaders headers = new HttpHeaders();
        String upstream = "aabbccdd00112233aabbccdd00112233";
        headers.set("X-Trace-Id", upstream);
        ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                url("/api/demo/ping"), HttpMethod.GET, new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {
                });
        assertEquals(upstream, resp.getHeaders().getFirst("X-Trace-Id"),
                "判定依据: 合法的上游标识必须被沿用并回传");
        assertEquals(upstream, String.valueOf(resp.getBody().get("traceId")));
        log.info("判定依据: 合法上游标识 {} 被端到端沿用", upstream);
    }

    private EndpointMetricsSnapshot metricsOf(String endpoint) {
        ResponseEntity<EndpointMetricsSnapshot[]> resp = restTemplate.getForEntity(
                url("/observability/metrics"), EndpointMetricsSnapshot[].class);
        return java.util.Arrays.stream(resp.getBody())
                .filter(m -> m.endpoint().equals(endpoint))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少接口指标: " + endpoint));
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
