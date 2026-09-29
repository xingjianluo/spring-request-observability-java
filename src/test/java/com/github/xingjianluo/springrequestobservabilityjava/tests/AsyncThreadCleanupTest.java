package com.github.xingjianluo.springrequestobservabilityjava.tests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.task.TaskExecutor;
import org.springframework.beans.factory.annotation.Qualifier;

import com.github.xingjianluo.springrequestobservabilityjava.observability.trace.TraceContext;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证多个异步请求复用线程池后，工作线程上的 traceId 被彻底清理，
 * 且每个异步请求拿到的都是自己的标识。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AsyncThreadCleanupTest {

    private static final Logger log = LoggerFactory.getLogger(AsyncThreadCleanupTest.class);

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    @Qualifier("applicationTaskExecutor")
    private TaskExecutor taskExecutor;

    @BeforeEach
    void resetMetrics() {
        restTemplate.postForEntity("http://localhost:" + port + "/observability/metrics/reset", null, Map.class);
    }

    @Test
    void pooledThreadsAreCleanAfterManyAsyncRequests() throws Exception {
        int n = 30;
        // 调用方使用独立线程池并发发起请求；applicationTaskExecutor 只承载应用内异步任务，
        // 避免与被测的异步工作线程相互占满造成线程饥饿。
        java.util.concurrent.ExecutorService callers =
                java.util.concurrent.Executors.newFixedThreadPool(12);
        CountDownLatch allReturned = new CountDownLatch(n);
        AtomicInteger ok = new AtomicInteger();
        for (int i = 0; i < n; i++) {
            callers.execute(() -> {
                try {
                    var resp = restTemplate.getForEntity(
                            "http://localhost:" + port + "/api/demo/async", Map.class);
                    int status = resp.getStatusCode().value();
                    String traceId = resp.getHeaders().getFirst("X-Trace-Id");
                    if (status == 200 && traceId != null
                            && traceId.equals(resp.getBody().get("traceId"))) {
                        ok.incrementAndGet();
                    } else {
                        log.warn("异步请求校验失败: status={}, traceId={}", status, traceId);
                    }
                } finally {
                    allReturned.countDown();
                }
            });
        }
        assertTrue(allReturned.await(60, TimeUnit.SECONDS));
        callers.shutdownNow();
        log.info("[异步清理] {} 个异步请求成功返回且头体标识一致", ok.get());
        assertEquals(n, ok.get(), "判定依据：全部异步请求的响应头/响应体 traceId 一致");

        // 等异步 onComplete 记账落盘后，向线程池投放清理探针：此时线程已无任何请求上下文。
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            long total = restTemplate.getForEntity(
                    "http://localhost:" + port + "/observability/metrics",
                    com.github.xingjianluo.springrequestobservabilityjava.observability.metrics.MetricsSnapshot.class)
                    .getBody().endpoints().get("GET /api/demo/async").total();
            if (total >= n) {
                break;
            }
            Thread.sleep(50);
        }

        int probes = 8;
        CountDownLatch probeDone = new CountDownLatch(probes);
        AtomicInteger clean = new AtomicInteger();
        for (int i = 0; i < probes; i++) {
            taskExecutor.execute(() -> {
                try {
                    if (TraceContext.getTraceId() == null) {
                        clean.incrementAndGet();
                    } else {
                        log.warn("发现线程残留 traceId={}", TraceContext.getTraceId());
                    }
                } finally {
                    probeDone.countDown();
                }
            });
        }
        assertTrue(probeDone.await(10, TimeUnit.SECONDS));
        log.info("[异步清理] 清理探针 {}/{} 在线程上未发现残留 traceId", clean.get(), probes);
        assertEquals(probes, clean.get(), "判定依据：异步任务结束后复用线程无 traceId 残留");
    }

    private static final class ResponseEntityHolder {
        int status;
        String traceId;

        @Override
        public String toString() {
            return "status=" + status + ", traceId=" + traceId;
        }
    }
}
