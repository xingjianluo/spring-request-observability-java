package com.github.xingjianluo.springrequestobservabilityjava.observability.web;

import com.github.xingjianluo.springrequestobservabilityjava.observability.metrics.EndpointMetricsRegistry;
import com.github.xingjianluo.springrequestobservabilityjava.observability.metrics.MetricsSnapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本地可观测性自检接口：
 * <ul>
 *     <li>{@code GET  /observability/metrics}：查看当前指标快照；</li>
 *     <li>{@code POST /observability/metrics/reset}：清零计数（建立新的验证基线）；</li>
 *     <li>{@code POST /observability/sample-rate?value=0.x}：运行时调整采样率，
 *         仅对之后进入的新请求生效。</li>
 * </ul>
 */
@RestController
@RequestMapping("/observability")
public class MetricsController {

    private final EndpointMetricsRegistry registry;

    public MetricsController(EndpointMetricsRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/metrics")
    public MetricsSnapshot metrics() {
        return registry.snapshot();
    }

    @PostMapping("/metrics/reset")
    public Map<String, Object> reset() {
        registry.reset();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reset", true);
        return result;
    }

    @PostMapping("/sample-rate")
    public Map<String, Object> sampleRate(@RequestParam("value") double value) {
        if (Double.isNaN(value) || value < 0.0d || value > 1.0d) {
            // 参数非法归为参数错误，由全局异常处理器统一返回 400。
            throw new IllegalArgumentException("sample-rate must be within [0,1]");
        }
        registry.setSampleRate(value);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sampleRate", registry.getSampleRate());
        result.put("note", "change applies only to requests entering afterwards");
        return result;
    }
}
