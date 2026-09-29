package com.github.xingjianluo.springrequestobservabilityjava.tests;

import com.github.xingjianluo.springrequestobservabilityjava.observability.trace.TraceContext;
import com.github.xingjianluo.springrequestobservabilityjava.observability.trace.TraceIdGenerator;
import com.github.xingjianluo.springrequestobservabilityjava.observability.trace.Tracing;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 链路标识生成与跨线程传播的单测。
 */
class TraceIdTest {

    private static final Logger log = LoggerFactory.getLogger(TraceIdTest.class);
    private static final Pattern HEX32 = Pattern.compile("^[0-9a-f]{32}$");

    @Test
    void generatedTraceIdsAreUniqueAndWellFormed() {
        int n = 10_000;
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < n; i++) {
            String id = TraceIdGenerator.generate();
            assertTrue(HEX32.matcher(id).matches(), "判定依据: 标识必须是 32 位小写 hex，实际=" + id);
            ids.add(id);
        }
        log.info("判定依据: 连续生成 {} 个 traceId，去重后 {} 个，期望相等（稳定且唯一）; 示例={}",
                n, ids.size(), TraceIdGenerator.generate());
        assertEquals(n, ids.size());
    }

    @Test
    void malformedIncomingTraceIdIsRejected() {
        String[] invalid = {"abc", "g".repeat(32), "0123456789abcdef\nINJECTED", null, ""};
        for (String value : invalid) {
            assertFalse(TraceIdGenerator.isValid(value),
                    "判定依据: 非法入站标识必须被拒绝，防止串号与日志注入: " + value);
        }
        String valid = TraceIdGenerator.generate();
        assertTrue(TraceIdGenerator.isValid(valid));
        log.info("判定依据: 合法标识 {} 通过校验，非法头（含换行注入）均被拒绝", valid);
    }

    @Test
    void traceContextIsScopedAndCleanedOnThreadReuse() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        String first = "066f9a2c7b1e4f3a9c0d8e1b2a3c4d5e";
        String second = "177f0b3d8c2f504bbd1e9f2c3d4e5f60";

        // 第一次复用：包装任务在工作线程上看到提交线程的快照，结束后工作线程被清理
        Future<String> f1 = submitWith(pool, first);
        assertEquals(first, f1.get());
        // 第二次复用同一批工作线程：必须看到 second，且任务结束后工作线程无 first 残留
        Future<String> f2 = submitWith(pool, second);
        assertEquals(second, f2.get());

        // 再提交一个「提交线程无上下文」的任务：工作线程必须为空（不能残留上一个请求的标识）
        Future<String> empty = pool.submit(() -> {
            // 手动模拟 Tracing.wrap 对无上下文的快照
            return Tracing.wrap(() -> TraceContext.getTraceId()).call();
        });
        assertNull(empty.get());
        log.info("判定依据: 固定 2 线程池连续提交 traceId={} 与 {}，任务内可见各自标识，任务后工作线程无残留",
                first, second);

        pool.shutdown();
    }

    private Future<String> submitWith(ExecutorService pool, String traceId) {
        // wrap 在提交线程上做快照；提交后立即清理当前线程也不影响已提交任务
        TraceContext.setTraceId(traceId);
        try {
            return pool.submit(Tracing.wrap(() -> {
                String seen = TraceContext.getTraceId();
                log.info("判定依据: 工作线程 {} 看到 traceId={}", Thread.currentThread().getName(), seen);
                return seen;
            }));
        } finally {
            TraceContext.clear();
        }
    }
}
