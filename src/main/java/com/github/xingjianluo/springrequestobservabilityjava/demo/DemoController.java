package com.github.xingjianluo.springrequestobservabilityjava.demo;

import com.github.xingjianluo.springrequestobservabilityjava.observability.metrics.ErrorCategory;
import com.github.xingjianluo.springrequestobservabilityjava.observability.trace.TraceContext;
import com.github.xingjianluo.springrequestobservabilityjava.observability.web.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 演示接口：正常、参数错误、业务拒绝、系统异常、异步链路。
 */
@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    /** 正常请求：回显当前链路标识。 */
    @GetMapping("/ping")
    public Map<String, String> ping() {
        log.info("处理 ping 请求");
        return Map.of("traceId", TraceContext.getTraceId(), "status", "ok");
    }

    /**
     * 路径变量请求：非数字 id 触发参数错误（同时验证 {id} 被聚合成一个接口维度）。
     */
    @GetMapping("/order/{id}")
    public Map<String, Object> getOrder(@PathVariable String id) {
        if (!id.matches("\\d+")) {
            throw new BizException(ErrorCategory.PARAM_ERROR, "订单 id 必须为数字");
        }
        log.info("查询订单: id={}", id);
        return Map.of("id", id, "traceId", TraceContext.getTraceId());
    }

    /**
     * 业务拒绝示例：请求体 {@code balance < 0} 时拒绝。
     */
    @PostMapping("/order")
    public Map<String, Object> createOrder(@RequestBody Map<String, Object> body) {
        Object balance = body.get("balance");
        if (!(balance instanceof Number number) || number.doubleValue() < 0) {
            throw new BizException(ErrorCategory.BIZ_REJECT, "余额不足，订单被业务规则拒绝");
        }
        log.info("创建订单: body={}", body);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("created", true);
        result.put("traceId", TraceContext.getTraceId());
        return result;
    }

    /**
     * 故意抛出底层组件原始异常（NPE），验证全局兜底：
     * 对外只收到脱敏文案，指标计入 SYSTEM_ERROR，traceId 仍然完整回传。
     */
    @GetMapping("/boom")
    public Map<String, Object> boom() {
        String value = null;
        return Map.of("length", value.length());
    }

    /**
     * 异步请求：在 traceAwareExecutor 工作线程执行，日志与返回值中的 traceId
     * 必须与请求线程一致；线程复用多次也不能串号。
     */
    @Async("traceAwareExecutor")
    @GetMapping("/async")
    public CompletableFuture<Map<String, Object>> async() {
        log.info("异步处理开始（请求线程上下文已快照传播）");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", TraceContext.getTraceId());
        result.put("thread", Thread.currentThread().getName());
        log.info("异步处理结束: thread={}", Thread.currentThread().getName());
        return CompletableFuture.completedFuture(result);
    }
}
