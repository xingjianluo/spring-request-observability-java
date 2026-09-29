package com.github.xingjianluo.springrequestobservabilityjava.observability.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 可观测性开关配置（前缀 {@code observability}）：
 * <ul>
 *     <li>{@code observability.enabled}：指标采集总开关。关闭后不再对新请求记录明细指标，
 *         但 traceId 的生成/传播不受影响；</li>
 *     <li>{@code observability.sample-rate}：明细指标采样率，取值 [0,1]，默认 1.0（全量）。
 *         采样与否在请求进入时一次性决定并贯穿整个请求。</li>
 * </ul>
 * 配置变更（包括运行时通过 /observability/sample-rate 调整）只影响变更后进入的新请求，
 * 已经记录的历史计数不会被追溯或改动。
 */
@ConfigurationProperties(prefix = "observability")
public class ObservabilityProperties {

    /** 指标采集总开关。traceId 链路始终开启，不受此开关影响。 */
    private boolean enabled = true;

    /** 明细指标采样率：1.0 = 全量，0.0 = 不采样（total 计数器仍然累加）。 */
    private double sampleRate = 1.0d;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double getSampleRate() {
        return sampleRate;
    }

    public void setSampleRate(double sampleRate) {
        // 非法值夹取到合法区间，避免配置错误导致采样口径不可解释。
        if (Double.isNaN(sampleRate)) {
            this.sampleRate = 1.0d;
        } else {
            this.sampleRate = Math.max(0.0d, Math.min(1.0d, sampleRate));
        }
    }
}
