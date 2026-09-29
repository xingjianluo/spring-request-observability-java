# Spring Boot 请求可观测性（轻量链路追踪 + 指标采集）

在不引入外部追踪系统的前提下，为每个 HTTP 请求提供：

1. **稳定唯一的链路标识 traceId**：返回给调用方、贯穿同步/异步/线程池场景的全部日志；
2. **按接口维度的指标**：调用次数、成功/失败分类、耗时分布，高并发下不丢不重；
3. **异常分类统计与响应脱敏**：参数错误 / 业务拒绝 / 系统异常分别统计，底层异常不外露。

所有能力均可在本地通过 HTTP 接口与单元/集成测试直接验证。

---

## 1. 快速开始

```bash
# 启动（默认 8080 端口）
mvn spring-boot:run

# 跑全部测试
mvn test
```

演示接口：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET  | `/api/demo/hello?name=xx` | 正常请求 |
| POST | `/api/demo/echo?value=xx` | 缺少 `value` 时触发参数错误（400） |
| GET  | `/api/demo/business-reject` | 业务拒绝（422） |
| GET  | `/api/demo/system-error` | 未处理系统异常（500，响应脱敏） |
| GET  | `/api/demo/async` | 异步处理，验证跨线程 traceId |

自检接口：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET  | `/observability/metrics` | 指标快照 |
| POST | `/observability/metrics/reset` | 清零全部计数（建立验证基线） |
| POST | `/observability/sample-rate?value=0.5` | 运行时调整采样率（[0,1]），只影响新请求 |

---

## 2. 链路标识（traceId）规则

### 2.1 生成

- 入口过滤器 `TraceFilter`（最高优先级）为每个请求确定 traceId。
- **优先沿用调用方传入**的 `X-Trace-Id` 请求头，但仅当它通过合法性校验：
  长度 16~64，且只包含字母、数字、连字符 `[A-Za-z0-9-]+`
  （拒绝换行/空格等，防止日志注入与 HTTP 响应头注入）。
- 传入值非法或缺失时，新生成一个 **32 位十六进制**标识：
  高 8 位为毫秒时间戳（大致有序），后 24 位为 `SecureRandom` 随机数，高并发下不冲突、不可预测。

### 2.2 传播

- 入口处同时写入三处：
  - HTTP 响应头 `X-Trace-Id`（异常时也会带上，调用方可凭此反馈问题）；
  - `ThreadLocal`（业务代码用 `TraceContext.getTraceId()` 读取）；
  - SLF4J `MDC`，日志 pattern 中的 `[%X{traceId}]` 会自动渲染到该线程的**每一条日志**。
- **异步 / 多线程**：
  - `TraceTaskDecorator` 注册在 Spring 的 `applicationTaskExecutor` 上，
    `@Async` 或显式提交到该执行器的任务，在**提交时捕获** traceId、执行线程上恢复、任务结束恢复/清理；
  - 不经过该执行器的任务（如自建线程池、`ForkJoinPool.commonPool`）需显式用
    `TraceContext.wrap(task)` 包装。
- **线程复用不残留**：过滤器在 `finally` 中成对清除 ThreadLocal 与 MDC；
  异步请求在 `AsyncListener` 完成回调中清除。`TraceContextTest` 与
  `AsyncThreadCleanupTest` 对复用线程做了无残留断言。
- 请求之间完全隔离，traceId 不串号（`TraceConcurrencyTest` 对 600 个并发请求校验唯一性）。

### 2.3 日志样例

```
20:37:11.870 INFO [http-nio-auto-1-exec-4] [ed2b483e43147704536880f2714ac77d] ...DemoController - handling hello, name=b0, traceId=...
```

方括号中即为当前请求的 traceId；无请求上下文时显示 `[]`。

---

## 3. 指标口径

### 3.1 维度

按 **HTTP 方法 + URI 模板**聚合（如 `GET /api/demo/hello`），`@PathVariable` 的具体值被归一化，避免高基数标签；未命中路由的请求归入 `UNMAPPED ...`。

### 3.2 计数器（与采样率无关，始终完整）

| 指标 | 含义 |
|------|------|
| `total` | 进入该接口的全部请求数。恒等于下面四类结果之和 |
| `success` / `clientError` / `businessRejection` / `serverError` | 2xx / 4xx(不含422) / 422 / 5xx 的次数 |
| `exceptionsByCategory` | `PARAM_ERROR`、`BUSINESS_REJECTION`、`SYSTEM_ERROR` 三类异常次数 |
| 全局 `totalRequests` | **所有** HTTP 请求（含 `/observability/**`，且关闭指标开关时仍计数） |
| 全局 `sampledRequests` | 被采入耗时样本的请求数 |

> **关键口径**：成功/失败/异常分类计数对每个请求都记录，因此即使采样率调成 0
> 或关闭明细采集，`success+clientError+businessRejection+serverError == total` 恒成立，
> 故障统计不缺数。

### 3.3 耗时分布（采样口径）

- 仅对被采样的请求记录：`count`（= `sampled`）、`minNanos`、`maxNanos`、`avgNanos`，
  以及毫秒直方图桶：`<=10ms, <=50ms, <=100ms, <=200ms, <=500ms, <=1000ms, +Inf`。
- 采样判定（伯努利）在**请求进入时做一次**并写入请求属性，贯穿整个请求，
  不会出现"记一半"；每个请求在同步出口或异步 `onComplete` **恰好记账一次**。
- 采样率默认 `1.0`（全量，此时耗时分布就是全量口径，各桶之和 == 请求数）。
- 并发安全：计数使用 `LongAdder`（分段累加），极值用 `AtomicLong` CAS，
  长时间运行与高并发下不丢不重（见 `TraceConcurrencyTest`：600 并发计数精确）。

### 3.4 开关与配置变更

- `observability.enabled=false`：不再记录接口明细指标；traceId 链路不受影响；全局流量总数仍计数。
- `observability.sample-rate` 可配置文件设置，也可运行时经
  `POST /observability/sample-rate` 调整。
- **配置变更只对变更后进入的新请求生效**；历史计数不追溯、不改动。
  非法采样率（越界/NaN）被拒绝（400），原值保持不变。
- `POST /observability/metrics/reset` 清零全部计数，测试/验证前建立干净基线。

### 3.5 快照样例

```bash
curl -s http://localhost:8080/observability/metrics | python3 -m json.tool
```

```json
{
  "metricsEnabled": true,
  "sampleRate": 1.0,
  "totalRequests": 12,
  "sampledRequests": 12,
  "endpoints": {
    "GET /api/demo/hello": {
      "total": 1, "sampled": 1,
      "success": 1, "clientError": 0, "businessRejection": 0, "serverError": 0,
      "exceptionsByCategory": { "PARAM_ERROR": 0, "BUSINESS_REJECTION": 0, "SYSTEM_ERROR": 0 },
      "count": 1, "minNanos": 1234567, "maxNanos": 1234567, "avgNanos": 1234567.0,
      "latencyBuckets": { "<= 10ms": 1, "<= 50ms": 0, "<= 100ms": 0, "<= 200ms": 0, "<= 500ms": 0, "<= 1000ms": 0, "+Inf": 0 }
    }
  }
}
```

---

## 4. 异常分类与脱敏

`GlobalExceptionHandler` 统一兜底，任何未处理异常都不会让 traceId 或计数残缺：

| 类别 | HTTP 状态 | 触发 | 对外 `message` |
|------|-----------|------|----------------|
| `PARAM_ERROR` | 400 | 缺参/类型不匹配/请求体不可读/`IllegalArgumentException` | 固定文案“请求参数不合法或缺失”，不含底层细节 |
| `BUSINESS_REJECTION` | 422 | `BusinessException` | 业务消息（面向用户，可透出） |
| `SYSTEM_ERROR` | 500 | 其余所有未预期异常 | 固定文案“服务暂时不可用，请稍后重试” |

- 错误响应统一为 `{ "traceId": "...", "error": "...", "message": "..." }`，并带 `X-Trace-Id` 头。
- 系统异常的**完整堆栈只写服务端日志**（日志含 traceId），便于排查；
  异常实现类名、底层组件信息不会出现在对外响应中（集成测试有断言）。
- 过滤器同时支持按状态码兜底分类，因此即便是绕过 `@RestControllerAdvice` 的错误也能被计数。

---

## 5. 本地验证步骤

```bash
mvn spring-boot:run
# 另开一个终端

# 0) 清零，建立基线
curl -s -X POST http://localhost:8080/observability/metrics/reset

# 1) 正常请求：观察响应头中的 X-Trace-Id，与响应体一致
curl -i -s 'http://localhost:8080/api/demo/hello?name=vincent' | grep -i 'X-Trace-Id'

# 2) 沿用上游标识：传入合法 id，返回应原样一致
curl -i -s -H 'X-Trace-Id: upstream-trace-0001-abcdabcdabcd' \
  'http://localhost:8080/api/demo/hello' | grep -i 'X-Trace-Id'

# 3) 异步请求：asyncThread 与 requestThread 不同，但 traceId 相同
curl -s 'http://localhost:8080/api/demo/async'

# 4) 三类异常（观察 HTTP 状态、error、message 脱敏、traceId）
curl -i -s -X POST 'http://localhost:8080/api/demo/echo'
curl -i -s 'http://localhost:8080/api/demo/business-reject'
curl -i -s 'http://localhost:8080/api/demo/system-error'

# 5) 采样率：置 0 后再发请求，total/success 仍增长，但 sampled/count/直方图不再增长
curl -s -X POST 'http://localhost:8080/observability/sample-rate?value=0'
curl -s 'http://localhost:8080/api/demo/hello?name=x'
curl -s 'http://localhost:8080/observability/metrics'

# 6) 查看服务端日志：同一请求的所有日志行（含异步线程）都带同一个 [%X{traceId}]
```

### 自动化测试覆盖

```bash
mvn test
```

| 测试类 | 覆盖点 |
|--------|--------|
| `TraceContextTest` | traceId 唯一生成、传入校验（防注入）、ThreadLocal/MDC 成对清理、wrap 跨线程传播、3200 次复用线程无串号 |
| `TraceFilterIntegrationTest` | 正常请求、沿用上游标识、异步跨线程传播且恰好记账一次、三类异常分类/脱敏/指标完整 |
| `TraceConcurrencyTest` | 600 并发请求 traceId 全局唯一、LongAdder 计数与直方图桶精确不丢不重 |
| `SamplingAndSwitchIntegrationTest` | 零采样下结果计数仍完整、运行时改采样率只影响新请求、非法值被拒 |
| `AsyncThreadCleanupTest` | 30 个异步请求后线程池线程无 traceId 残留 |

测试日志中会打印每个场景的 traceId 与“判定依据”，方便人工核对。

---

## 6. 关键组件一览

```
observability/
├── trace/
│   ├── TraceContext          # traceId 生成/校验、ThreadLocal+MDC、wrap 跨线程
│   ├── TraceTaskDecorator    # 装配到 Spring 异步线程池，自动传播/清理
│   └── TraceFilter           # 入口：定标识、定时长与采样决策、同步/异步恰好记账一次
├── config/
│   ├── ObservabilityProperties  # observability.enabled / sample-rate
│   └── ObservabilityConfig      # 装饰器与过滤器注册
├── metrics/
│   ├── Outcome / ErrorCategory  # 结果大类、异常分类
│   ├── EndpointMetrics          # 单接口的并发计数器与耗时直方图
│   ├── EndpointMetricsRegistry  # 按接口聚合、采样判定、全局计数、快照/重置
│   ├── EndpointView / MetricsSnapshot  # 对外只读视图
└── web/
    ├── GlobalExceptionHandler  # 三类异常分类、脱敏、错误结构
    ├── BusinessException       # 业务拒绝异常
    ├── ErrorResponse           # 统一错误响应体
    └── MetricsController       # /observability/** 自检接口
```
