package com.github.xingjianluo.springrequestobservabilityjava.web.demo;

import com.github.xingjianluo.springrequestobservabilityjava.observability.web.BusinessException;
import com.github.xingjianluo.springrequestobservabilityjava.observability.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.springframework.core.task.TaskExecutor;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * 用于本地验证可观测性的演示接口：
 * <pre>
 * GET  /api/demo/hello?name=xx        正常请求（200）
 * POST /api/demo/echo?value=          参数缺失触发参数错误（400）
 * GET  /api/demo/business-reject      业务拒绝（422）
 * GET  /api/demo/system-error         未处理系统异常（500，对外脱敏）
 * GET  /api/demo/async                异步处理，验证跨线程 traceId 传播
 * </pre>
 */
@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    private final TaskExecutor applicationTaskExecutor;

    public DemoController(@Qualifier("applicationTaskExecutor") TaskExecutor applicationTaskExecutor) {
        this.applicationTaskExecutor = applicationTaskExecutor;
    }

    @GetMapping("/hello")
    public Map<String, Object> hello(@RequestParam(value = "name", defaultValue = "world") String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        String traceId = TraceContext.getTraceId();
        log.info("handling hello, name={}, traceId={}", name, traceId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "hello, " + name);
        body.put("traceId", traceId);
        return body;
    }

    @PostMapping("/echo")
    public Map<String, Object> echo(@RequestParam("value") String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("echo", value);
        body.put("traceId", TraceContext.getTraceId());
        return body;
    }

    @GetMapping("/business-reject")
    public Map<String, Object> businessReject() {
        log.warn("business rule rejected the request, traceId={}", TraceContext.getTraceId());
        throw new BusinessException("余额不足，无法完成本次操作");
    }

    @GetMapping("/system-error")
    public Map<String, Object> systemError() {
        log.info("about to trigger an unexpected error, traceId={}", TraceContext.getTraceId());
        // 模拟底层组件抛出的未预期异常；处理器会对外脱敏，只在服务端日志留堆栈。
        throw new IllegalStateException("NPE-ish low-level failure at component X#doWork");
    }

    @GetMapping("/async")
    public CompletableFuture<Map<String, Object>> async() {
        String requestThread = Thread.currentThread().getName();
        log.info("async endpoint entered on thread={}, traceId={}", requestThread, TraceContext.getTraceId());
        // 不显式 wrap：@Async 使用的 applicationTaskExecutor 已配置 TraceTaskDecorator，
        // traceId 会自动传播到执行线程。
        return CompletableFuture.supplyAsync(() -> {
            String asyncThread = Thread.currentThread().getName();
            String traceId = TraceContext.getTraceId();
            log.info("async work running on thread={}, traceId={}", asyncThread, traceId);
            if (traceId == null) {
                throw new IllegalStateException("traceId lost in async thread");
            }
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("requestThread", requestThread);
            body.put("asyncThread", asyncThread);
            body.put("traceId", traceId);
            return body;
        }, applicationTaskExecutor);
    }
}
