package com.github.xingjianluo.springrequestobservabilityjava.observability.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 可观测性配置（前缀 {@code observability}）。
 *
 * <ul>
 *   <li>{@code observability.metrics-enabled}：指标采集总开关，默认开启。
 *       关闭期间不产生任何接口指标（观测总数也不计），重新开启后继续累加；
 *       关闭期间不做任何隐式重置，因此「开 - 关 - 开」各区间的口径各自连续、可解释。</li>
 *   <li>{@code observability.sampling-rate}：详细指标（耗时分布、成败计数）的采样率，
 *       取值 [0,1]，默认 1.0（全量）。判定在每个请求进入时做一次，整个请求生命周期固定不变；
 *       无论是否采样命中，{@code observedTotal} 始终 +1，作为全量分母，
 *       其余细项仅在采样命中时记录，口径为「采样命中的请求」。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "observability")
public class ObservabilityProperties {

    private boolean metricsEnabled = true;
    private double samplingRate = 1.0d;

    public boolean isMetricsEnabled() {
        return metricsEnabled;
    }

    public void setMetricsEnabled(boolean metricsEnabled) {
        this.metricsEnabled = metricsEnabled;
    }

    public double getSamplingRate() {
        return samplingRate;
    }

    /**
     * 设置采样率并规整到 [0,1]，非法值（NaN/越界）不会污染统计口径：
     * 小于 0 按 0 处理，大于 1/NaN 按 1 处理。
     */
    public void setSamplingRate(double samplingRate) {
        if (Double.isNaN(samplingRate) || samplingRate > 1.0d) {
            this.samplingRate = 1.0d;
        } else if (samplingRate < 0.0d) {
            this.samplingRate = 0.0d;
        } else {
            this.samplingRate = samplingRate;
        }
    }
}
