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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端集成测试：启动真实 Servlet 容器（随机端口）发起 HTTP 请求，
 * 覆盖正常请求、传入标识沿用、异步链路、异常分支与指标口径。
 * 每个断言都打印 traceId 与判定依据，便于在测试日志中人工核对。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TraceFilterIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(TraceFilterIntegrationTest.class);

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void resetMetrics() {
        restTemplate.postForEntity(url("/observability/metrics/reset"), null, Map.class);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void normalRequestCarriesGeneratedTraceIdAndCountsSuccess() {
        ResponseEntity<Map> response = restTemplate.getForEntity(url("/api/demo/hello?name=vincent"), Map.class);
        String traceId = response.getHeaders().getFirst("X-Trace-Id");

        log.info("[正常请求] status={} X-Trace-Id={} body={}", response.getStatusCode(), traceId, response.getBody());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(traceId, "判定依据：每个响应都必须带回 X-Trace-Id 头");
        assertEquals(32, traceId.length(), "判定依据：生成的标识为 32 位十六进制");
        assertEquals(traceId, response.getBody().get("traceId"),
                "判定依据：响应体中的 traceId 与响应头一致，说明标识贯穿处理过程");

        MetricsSnapshot snapshot = currentSnapshot();
        EndpointView view = snapshot.endpoints().get("GET /api/demo/hello");
        log.info("[正常请求-指标] total={} sampled={} success={} traceId={}",
                view.total(), view.sampled(), view.success(), traceId);
        // 按接口维度核对（/observability/metrics 自身的调用只计入全局 total，不计入该业务接口）
        assertEquals(1, view.total());
        assertEquals(1, view.success());
        assertEquals(1, view.sampled(), "判定依据：默认全量采样，样本数等于请求数");
        assertEquals(1, view.count());
        assertTrue(view.latencyBuckets().values().stream().mapToLong(Long::longValue).sum() == 1,
                "判定依据：耗时直方图各桶之和恰为样本数 1");
    }

    @Test
    void validIncomingTraceIdIsHonoredAndReturned() {
        String incoming = "caller-trace-0001-abcdefabcdefab";
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Trace-Id", incoming);
        ResponseEntity<Map> response = restTemplate.exchange(
                url("/api/demo/hello?name=upstream"), HttpMethod.GET, new HttpEntity<>(headers), Map.class);

        String returned = response.getHeaders().getFirst("X-Trace-Id");
        log.info("[传入标识] incoming={} returned={}", incoming, returned);
        assertEquals(incoming, returned, "判定依据：合法的上游标识被沿用并原样返回");
        assertEquals(incoming, response.getBody().get("traceId"), "判定依据：处理期间读取到的也是上游标识");
    }

    @Test
    void asyncRequestPropagatesTraceIdIntoWorkerThreadAndRecordedOnce() throws Exception {
        ResponseEntity<Map> response = restTemplate.getForEntity(url("/api/demo/async"), Map.class);
        String traceId = response.getHeaders().getFirst("X-Trace-Id");

        log.info("[异步请求] status={} X-Trace-Id={} body={}", response.getStatusCode(), traceId, response.getBody());
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(traceId);
        Map body = response.getBody();
        assertEquals(traceId, body.get("traceId"),
                "判定依据：异步工作线程中读到的 traceId 与请求线程一致（TaskDecorator 传播成功）");
        org.junit.jupiter.api.Assertions.assertNotEquals(body.get("requestThread"), body.get("asyncThread"),
                "判定依据：工作确实发生在另一个线程上");

        // 异步记账发生在 onComplete，可能略晚于响应体返回，轮询等待其落账。
        EndpointView view = waitForEndpoint("GET /api/demo/async", 1);
        log.info("[异步请求-指标] total={} success={} count={} traceId={}",
                view.total(), view.success(), view.count(), traceId);
        assertEquals(1, view.total());
        assertEquals(1, view.success(), "判定依据：异步成功请求仍计入成功");
        assertEquals(1, view.count(), "判定依据：异步请求恰好记账一次，不丢不重");
    }

    @Test
    void exceptionBranchesAreClassifiedAndSanitizedWithoutLosingMetrics() {
        // 1) 参数错误（缺少必填参数 value -> 框架 MissingServletRequestParameterException -> 400）
        ResponseEntity<Map> paramResp = restTemplate.postForEntity(url("/api/demo/echo"), null, Map.class);
        String paramTraceId = paramResp.getHeaders().getFirst("X-Trace-Id");
        log.info("[参数错误] status={} traceId={} body={}", paramResp.getStatusCode(), paramTraceId,
                paramResp.getBody());
        assertEquals(HttpStatus.BAD_REQUEST, paramResp.getStatusCode());
        assertSanitizedError(paramResp, paramTraceId, "PARAM_ERROR");

        // 2) 业务拒绝（422，消息可透出）
        ResponseEntity<Map> bizResp = restTemplate.getForEntity(url("/api/demo/business-reject"), Map.class);
        String bizTraceId = bizResp.getHeaders().getFirst("X-Trace-Id");
        log.info("[业务拒绝] status={} traceId={} body={}", bizResp.getStatusCode(), bizTraceId, bizResp.getBody());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, bizResp.getStatusCode());
        assertSanitizedError(bizResp, bizTraceId, "BUSINESS_REJECTION");

        // 3) 系统异常（500，对外不得透出底层原始异常文本）
        ResponseEntity<Map> sysResp = restTemplate.getForEntity(url("/api/demo/system-error"), Map.class);
        String sysTraceId = sysResp.getHeaders().getFirst("X-Trace-Id");
        log.info("[系统异常] status={} traceId={} body={}", sysResp.getStatusCode(), sysTraceId, sysResp.getBody());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, sysResp.getStatusCode());
        assertSanitizedError(sysResp, sysTraceId, "SYSTEM_ERROR");
        String sysMessage = String.valueOf(sysResp.getBody().get("message"));
        org.junit.jupiter.api.Assertions.assertFalse(sysMessage.contains("X#doWork"),
                "判定依据：底层组件原始异常文本不得透出到对外响应");
        org.junit.jupiter.api.Assertions.assertFalse(sysMessage.contains("IllegalStateException"),
                "判定依据：异常实现类名不得透出");

        // 三个 traceId 互不相同，请求之间不串号。
        org.junit.jupiter.api.Assertions.assertNotEquals(paramTraceId, bizTraceId);
        org.junit.jupiter.api.Assertions.assertNotEquals(bizTraceId, sysTraceId);

        // 指标核对：异常分类计数完整，且结果计数之和 == total。
        EndpointView echo = currentSnapshot().endpoints().get("POST /api/demo/echo");
        EndpointView biz = currentSnapshot().endpoints().get("GET /api/demo/business-reject");
        EndpointView sys = currentSnapshot().endpoints().get("GET /api/demo/system-error");

        log.info("[异常指标] echo: total={} clientError={} ex={}", echo.total(), echo.clientError(),
                echo.exceptionsByCategory());
        log.info("[异常指标] biz : total={} businessRejection={} ex={}", biz.total(), biz.businessRejection(),
                biz.exceptionsByCategory());
        log.info("[异常指标] sys : total={} serverError={} ex={}", sys.total(), sys.serverError(),
                sys.exceptionsByCategory());

        assertEquals(1, echo.total());
        assertEquals(1, echo.clientError());
        assertEquals(1L, echo.exceptionsByCategory().get("PARAM_ERROR"));

        assertEquals(1, biz.total());
        assertEquals(1, biz.businessRejection());
        assertEquals(1L, biz.exceptionsByCategory().get("BUSINESS_REJECTION"));

        assertEquals(1, sys.total());
        assertEquals(1, sys.serverError());
        assertEquals(1L, sys.exceptionsByCategory().get("SYSTEM_ERROR"));

        for (EndpointView v : java.util.List.of(echo, biz, sys)) {
            long sum = v.success() + v.clientError() + v.businessRejection() + v.serverError();
            assertEquals(v.total(), sum, "判定依据：四类结果计数之和恒等于总数，统计口径完整可解释");
        }
    }

    private void assertSanitizedError(ResponseEntity<Map> response, String traceId, String expectedError) {
        Map body = response.getBody();
        assertNotNull(body, "判定依据：异常时仍返回统一错误结构而不是容器默认错误页");
        assertEquals(traceId, body.get("traceId"), "判定依据：错误响应体中带回同一 traceId");
        assertEquals(expectedError, body.get("error"), "判定依据：错误码按类别区分");
        assertNotNull(body.get("message"));
    }

    private EndpointView waitForEndpoint(String name, long expectedTotal) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        EndpointView view = null;
        while (System.currentTimeMillis() < deadline) {
            view = currentSnapshot().endpoints().get(name);
            if (view != null && view.total() >= expectedTotal) {
                return view;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("等待接口 " + name + " 指标落账超时，最新视图=" + view);
    }

    @SuppressWarnings("unchecked")
    private MetricsSnapshot currentSnapshot() {
        ResponseEntity<Map> entity = restTemplate.getForEntity(url("/observability/metrics"), Map.class);
        Map<String, Object> body = entity.getBody();
        assertNotNull(body);
        boolean enabled = Boolean.TRUE.equals(body.get("metricsEnabled"));
        double sampleRate = ((Number) body.get("sampleRate")).doubleValue();
        long total = ((Number) body.get("totalRequests")).longValue();
        long sampled = ((Number) body.get("sampledRequests")).longValue();
        Map<String, EndpointView> endpoints = new java.util.TreeMap<>();
        Map<String, Object> rawEndpoints = (Map<String, Object>) body.get("endpoints");
        rawEndpoints.forEach((name, raw) -> {
            Map<String, Object> v = (Map<String, Object>) raw;
            endpoints.put(name, new EndpointView(
                    name,
                    asLong(v.get("total")),
                    asLong(v.get("sampled")),
                    asLong(v.get("success")),
                    asLong(v.get("clientError")),
                    asLong(v.get("businessRejection")),
                    asLong(v.get("serverError")),
                    toStringLongMap(v.get("exceptionsByCategory")),
                    asLong(v.get("count")),
                    asLong(v.get("minNanos")),
                    asLong(v.get("maxNanos")),
                    asLong(v.get("avgNanos")).doubleValue(),
                    toStringLongMap(v.get("latencyBuckets"))));
        });
        return new MetricsSnapshot(enabled, sampleRate, total, sampled, endpoints);
    }

    private static Long asLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> toStringLongMap(Object value) {
        Map<String, Long> result = new java.util.LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((k, v) -> result.put(String.valueOf(k), asLong(v)));
        }
        return result;
    }
}
