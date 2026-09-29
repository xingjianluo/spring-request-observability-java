package com.github.xingjianluo.springrequestobservabilityjava.tests;

import com.github.xingjianluo.springrequestobservabilityjava.observability.trace.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TraceContext} 的纯单元测试：
 * 覆盖生成唯一性、传入标识合法性、ThreadLocal/MDC 成对清理、
 * wrap() 跨线程传播，以及线程池复用线程时不残留上一个请求标识。
 */
class TraceContextTest {

    private static final Logger log = LoggerFactory.getLogger(TraceContextTest.class);

    @AfterEach
    void cleanUp() {
        TraceContext.clear();
    }

    @Test
    void generatedTraceIdIsStableAndUnique() {
        String first = TraceContext.generateTraceId();
        assertNotNull(first);
        assertEquals(32, first.length(), "判定依据：traceId 固定为 32 位十六进制");
        assertTrue(first.matches("[0-9a-f]{32}"), "判定依据：只包含十六进制小写字符");

        List<String> ids = new ArrayList<>();
        int n = 5_000;
        for (int i = 0; i < n; i++) {
            ids.add(TraceContext.generateTraceId());
        }
        long distinct = ids.stream().distinct().count();
        log.info("生成 traceId 示例: first={}，{} 次生成中去重后为 {} 个", first, n, distinct);
        assertEquals(n, distinct, "判定依据：5000 次生成无一重复");
    }

    @Test
    void validatesIncomingTraceId() {
        String good = "abcd1234efgh5678ABCD-9012";
        log.info("合法传入标识校验 candidate={}, result={}", good, TraceContext.isValidIncomingId(good));
        assertTrue(TraceContext.isValidIncomingId(good));

        String badNewline = "abcdef123456\nX-Trace-Id: injected";
        String tooShort = "abc";
        String withSpace = "abcd1234 efgh5678abcd1234";
        log.info("非法传入标识判定: newline={} short={} space={}",
                TraceContext.isValidIncomingId(badNewline),
                TraceContext.isValidIncomingId(tooShort),
                TraceContext.isValidIncomingId(withSpace));
        assertFalse(TraceContext.isValidIncomingId(badNewline), "判定依据：含换行的标识必须拒绝，防日志/响应头注入");
        assertFalse(TraceContext.isValidIncomingId(tooShort), "判定依据：过短标识拒绝");
        assertFalse(TraceContext.isValidIncomingId(withSpace), "判定依据：含空格标识拒绝");
        assertFalse(TraceContext.isValidIncomingId(null));
    }

    @Test
    void setAndClearArePairedBetweenThreadLocalAndMdc() {
        String traceId = TraceContext.generateTraceId();
        TraceContext.setTraceId(traceId);

        assertEquals(traceId, TraceContext.getTraceId());
        assertEquals(traceId, MDC.get(TraceContext.MDC_KEY), "判定依据：set 后 MDC 必须同步可见");
        log.info("已设置 traceId={}，接下来校验清理", traceId);

        TraceContext.clear();
        assertNull(TraceContext.getTraceId());
        assertNull(MDC.get(TraceContext.MDC_KEY), "判定依据：clear 后 MDC 必须同步移除");
        log.info("清理后 ThreadLocal 与 MDC 均为空，traceId 不会残留到复用线程");
    }

    @Test
    void wrapPropagatesTraceIdAndRestoresThreadState() throws Exception {
        String parentTraceId = TraceContext.generateTraceId();
        TraceContext.setTraceId(parentTraceId);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            String[] seenInWorker = new String[1];
            CountDownLatch done = new CountDownLatch(1);
            pool.execute(TraceContext.wrap(() -> {
                seenInWorker[0] = TraceContext.getTraceId();
                log.info("工作线程中读取到 traceId={}（应与提交方一致）", TraceContext.getTraceId());
                done.countDown();
            }));
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(parentTraceId, seenInWorker[0], "判定依据：wrap 后异步线程继承提交方 traceId");

            // 任务结束后，池内线程不得残留该标识。
            CountDownLatch cleanCheck = new CountDownLatch(1);
            String[] leftover = new String[1];
            String[] mdcLeftover = new String[1];
            pool.execute(() -> {
                leftover[0] = TraceContext.getTraceId();
                mdcLeftover[0] = MDC.get(TraceContext.MDC_KEY);
                cleanCheck.countDown();
            });
            assertTrue(cleanCheck.await(5, TimeUnit.SECONDS));
            log.info("线程复用后残留检查: threadLocal={}, mdc={}", leftover[0], mdcLeftover[0]);
            assertNull(leftover[0], "判定依据：复用线程上 ThreadLocal 无残留");
            assertNull(mdcLeftover[0], "判定依据：复用线程上 MDC 无残留");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentRequestsDoNotCrossTalkOnReusedThreads() throws Exception {
        int threads = 16;
        int iterations = 200;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        AtomicInteger mismatches = new AtomicInteger();
        CountDownLatch allDone = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int workerIndex = t;
            pool.execute(() -> {
                try {
                    for (int i = 0; i < iterations; i++) {
                        String traceId = TraceContext.generateTraceId();
                        try {
                            TraceContext.setTraceId(traceId);
                            // 模拟处理过程中的读取，应始终读到本任务自己的标识。
                            if (!traceId.equals(TraceContext.getTraceId())
                                    || !traceId.equals(MDC.get(TraceContext.MDC_KEY))) {
                                mismatches.incrementAndGet();
                            }
                        } finally {
                            TraceContext.clear();
                        }
                    }
                    log.info("worker#{} 完成 {} 轮，全部在同一组复用线程上", workerIndex, iterations);
                } finally {
                    allDone.countDown();
                }
            });
        }

        assertTrue(allDone.await(30, TimeUnit.SECONDS));
        pool.shutdownNow();
        int total = threads * iterations;
        log.info("高并发复用线程校验: 总请求={}, 串号/残留次数={}", total, mismatches.get());
        assertEquals(0, mismatches.get(), "判定依据：" + total + " 次复用线程执行无一次串号或残留");
    }
}
