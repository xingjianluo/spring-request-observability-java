package com.github.xingjianluo.springrequestobservabilityjava.tests;

import com.github.xingjianluo.springrequestobservabilityjava.observability.metrics.EndpointView;
import com.github.xingjianluo.springrequestobservabilityjava.observability.metrics.MetricsSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 采样率与开关的口径一致性测试：
 * <ul>
 *     <li>采样率 0：成功/失败等结果计数仍然完整（口径与采样无关），耗时样本为 0；</li>
 *     <li>运行时改采样率：只影响之后进入的新请求，历史计数不变、可解释；</li>
 *     <li>reset：回到零基线，便于反复验证。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SamplingAndSwitchIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SamplingAndSwitchIntegrationTest.class);
    private static final String HELLO = "GET /api/demo/hello";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void resetAll() {
        restTemplate.postForEntity(url("/observability/metrics/reset"), null, Map.class);
    }

    @AfterEach
    void restoreRate() {
        setSampleRate(1.0d);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private void setSampleRate(double value) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("value", String.valueOf(value));
        restTemplate.postForEntity(url("/observability/sample-rate?value=" + value), null, Map.class);
    }

    private EndpointView helloView() {
        ResponseEntity<MetricsSnapshot> entity =
                restTemplate.getForEntity(url("/observability/metrics"), MetricsSnapshot.class);
        return entity.getBody().endpoints().get(HELLO);
    }

    @Test
    void zeroSampleRateKeepsOutcomeCountsButDropsLatencySamples() {
        setSampleRate(0.0d);
        log.info("[采样率0] 采样率已设置为 0，发送 5 个正常请求 + 1 个业务拒绝请求");

        for (int i = 0; i < 5; i++) {
            assertEquals(HttpStatus.OK,
                    restTemplate.getForEntity(url("/api/demo/hello?name=s" + i), Map.class).getStatusCode());
        }
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY,
                restTemplate.getForEntity(url("/api/demo/business-reject"), Map.class).getStatusCode());

        EndpointView hello = helloView();
        log.info("[采样率0-指标] hello.total={} hello.success={} hello.sampled={} hello.count={}",
                hello.total(), hello.success(), hello.sampled(), hello.count());

        assertEquals(5, hello.total(), "判定依据：即使采样率为 0，请求总数仍精确统计");
        assertEquals(5, hello.success(), "判定依据：成功/失败口径与采样无关，始终完整");
        assertEquals(0, hello.sampled(), "判定依据：采样率 0 时无耗时样本");
        assertEquals(0, hello.count(), "判定依据：没有样本就没有 min/max/avg/直方图");

        EndpointView biz =
                restTemplate.getForObject(url("/observability/metrics"), MetricsSnapshot.class)
                        .endpoints().get("GET /api/demo/business-reject");
        assertEquals(1, biz.total());
        assertEquals(1, biz.businessRejection(), "判定依据：异常分支在零采样下依然被分类计数");
        assertEquals(1L, biz.exceptionsByCategory().get("BUSINESS_REJECTION"));
    }

    @Test
    void changingSampleRateAtRuntimeAffectsOnlyNewRequests() {
        // 阶段一：全量采样 3 次
        for (int i = 0; i < 3; i++) {
            restTemplate.getForEntity(url("/api/demo/hello?name=a" + i), Map.class);
        }
        EndpointView afterPhase1 = helloView();
        log.info("[运行时调整] 阶段一(rate=1): total={} sampled={}", afterPhase1.total(), afterPhase1.sampled());
        assertEquals(3, afterPhase1.total());
        assertEquals(3, afterPhase1.sampled());

        // 阶段二：运行时把采样率调成 0，再发 4 次
        setSampleRate(0.0d);
        for (int i = 0; i < 4; i++) {
            restTemplate.getForEntity(url("/api/demo/hello?name=b" + i), Map.class);
        }
        EndpointView afterPhase2 = helloView();
        log.info("[运行时调整] 阶段二(rate=0): total={} sampled={}", afterPhase2.total(), afterPhase2.sampled());
        assertEquals(7, afterPhase2.total(), "判定依据：total 持续精确累加");
        assertEquals(3, afterPhase2.sampled(),
                "判定依据：样本数停在阶段一的 3，配置变更只影响之后进入的新请求，历史口径不变、可解释");
        assertEquals(3, afterPhase2.count());
        assertEquals(7, afterPhase2.success(), "判定依据：全部 7 次成功都被计入，不受采样率切换影响");
    }

    @Test
    void invalidSampleRateIsRejectedAndOldRateKept() {
        setSampleRate(0.25d);
        ResponseEntity<Map> resp =
                restTemplate.postForEntity(url("/observability/sample-rate?value=1.5"), null, Map.class);
        log.info("[非法采样率] status={} body={}", resp.getStatusCode(), resp.getBody());
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(), "判定依据：越界采样率被归为参数错误");

        Double current =
                restTemplate.getForObject(url("/observability/metrics"), MetricsSnapshot.class).sampleRate();
        assertEquals(0.25d, current, 0.0001d, "判定依据：非法设置被拒绝，原采样率保持不变");
    }
}
