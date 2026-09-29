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

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 异步链路与异常分支的全栈集成测试。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AsyncTraceIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AsyncTraceIntegrationTest.class);

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void asyncRequestPropagatesSameTraceIdToWorkerThreadAndReusesThreadsCleanly() {
        int total = 30;
        Set<String> asyncTraceIds = new HashSet<>();
        Set<String> workerThreads = new HashSet<>();
        for (int i = 0; i < total; i++) {
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    url("/api/demo/async"), HttpMethod.GET, null,
                    new ParameterizedTypeReference<>() {
                    });
            String headerId = resp.getHeaders().getFirst("X-Trace-Id");
            Map<String, Object> body = resp.getBody();
            String bodyId = String.valueOf(body.get("traceId"));
            String thread = String.valueOf(body.get("thread"));
            assertEquals(200, resp.getStatusCode().value());
            assertEquals(headerId, bodyId,
                    "判定依据: 异步工作线程看到的 traceId 必须与响应头一致，请求=" + i);
            asyncTraceIds.add(headerId);
            workerThreads.add(thread);
            log.info("判定依据: 异步请求#{} traceId={} 工作线程={}（同一线程复用但标识不残留）", i, headerId, thread);
        }
        assertEquals(total, asyncTraceIds.size(),
                "判定依据: " + total + " 个异步请求标识必须各不相同");
        assertTrue(workerThreads.size() < total,
                "判定依据: 工作线程被复用（线程数=" + workerThreads.size() + " < 请求数），用以验证复用不串号");

        EndpointMetricsSnapshot asyncMetrics = metricsOf("GET /api/demo/async");
        assertTrue(asyncMetrics.observedTotal() >= total,
                "判定依据: AsyncListener 在 onComplete 记录指标，异步观测总数应包含全部请求");
        assertTrue(asyncMetrics.successCount() >= total,
                "判定依据: 异步成功请求计入 SUCCESS，实际 success=" + asyncMetrics.successCount());
        log.info("判定依据: 异步指标 observedTotal={} success={}，AsyncListener 恰好记录一次",
                asyncMetrics.observedTotal(), asyncMetrics.successCount());
    }

    @Test
    void exceptionBranchesAreClassifiedSanitizedAndCounted() {
        // 1) 参数错误：非法订单 id
        ResponseEntity<Map> paramResp = restTemplate.getForEntity(url("/api/demo/order/abc"), Map.class);
        assertError(paramResp, 400, "PARAM_ERROR");
        String traceId = paramResp.getHeaders().getFirst("X-Trace-Id");
        assertEquals(traceId, String.valueOf(paramResp.getBody().get("traceId")),
                "判定依据: 异常响应体也必须带正确的 traceId");
        log.info("判定依据: 参数错误 status=400 category=PARAM_ERROR traceId={}", traceId);

        // 2) 业务拒绝：负余额
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        ResponseEntity<Map<String, Object>> bizResp = restTemplate.exchange(
                url("/api/demo/order"), HttpMethod.POST,
                new HttpEntity<>("{\"balance\":-1}", headers),
                new ParameterizedTypeReference<>() {
                });
        assertError(bizResp, 409, "BIZ_REJECT");
        log.info("判定依据: 业务拒绝 status=409 category=BIZ_REJECT traceId={}",
                bizResp.getHeaders().getFirst("X-Trace-Id"));

        // 3) 系统异常：底层 NPE 不透出原始信息
        ResponseEntity<Map> sysResp = restTemplate.getForEntity(url("/api/demo/boom"), Map.class);
        assertError(sysResp, 500, "SYSTEM_ERROR");
        String raw = sysResp.getBody().toString();
        assertFalse(raw.contains("NullPointerException"),
                "判定依据: 对外响应不得包含底层组件原始异常类型，实际=" + raw);
        assertFalse(raw.contains("Cannot invoke"),
                "判定依据: 对外响应不得包含底层异常堆栈文案，实际=" + raw);
        log.info("判定依据: 系统异常 status=500 category=SYSTEM_ERROR，响应已脱敏: {}，traceId 仍完整: {}",
                raw, sysResp.getHeaders().getFirst("X-Trace-Id"));

        // 4) 三类指标各自累计，且 /api/demo/order/{id} 与 /api/demo/order 是不同维度
        EndpointMetricsSnapshot param = metricsOf("GET /api/demo/order/{id}");
        EndpointMetricsSnapshot order = metricsOf("POST /api/demo/order");
        EndpointMetricsSnapshot boom = metricsOf("GET /api/demo/boom");
        assertTrue(param.paramErrorCount() >= 1, "判定依据: 参数错误计数 +1");
        assertTrue(order.bizRejectCount() >= 1, "判定依据: 业务拒绝计数 +1");
        assertTrue(boom.systemErrorCount() >= 1, "判定依据: 系统异常计数 +1");
        log.info("判定依据: 分类指标 paramError={}(GET /order/{{id}}), bizReject={}(POST /order), systemError={}(GET /boom)",
                param.paramErrorCount(), order.bizRejectCount(), boom.systemErrorCount());
    }

    private void assertError(ResponseEntity<?> resp, int status, String category) {
        assertEquals(status, resp.getStatusCode().value(), "判定依据: HTTP 状态必须是 " + status);
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertEquals(category, String.valueOf(body.get("error")),
                "判定依据: error 字段必须是 " + category);
    }

    private EndpointMetricsSnapshot metricsOf(String endpoint) {
        ResponseEntity<EndpointMetricsSnapshot[]> resp = restTemplate.getForEntity(
                url("/observability/metrics"), EndpointMetricsSnapshot[].class);
        return Arrays.stream(resp.getBody())
                .filter(m -> m.endpoint().equals(endpoint))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少接口指标: " + endpoint));
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
