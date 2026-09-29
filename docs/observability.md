# 请求追踪与指标采集说明

本服务内置了一层轻量级、无外部中间件依赖的可观测性能力（链路追踪 + 接口指标 + 异常分类），
全部由 `observability` 包内的组件实现，可在本地直接验证。

- 链路组件：`observability.trace`（`TraceContext` / `TraceIdGenerator` / `Tracing`）、
  `observability.web.TraceFilter`、`observability.config.AsyncConfig`
- 指标组件：`observability.metrics`（`MetricsRegistry` / `EndpointMetricsSnapshot` / `ErrorCategory`）
- 异常组件：`observability.web.GlobalExceptionHandler` / `BizException` / `ApiErrorResponse`
- 指标查看：`GET /observability/metrics`

---

## 1. 链路标识（traceId）

### 1.1 生成规则

- 每个进入服务的 HTTP 请求由最外层 `TraceFilter` 分配一个 traceId。
- 格式：**32 位小写十六进制（128 bit）**，由
  - 48 bit 毫秒时间戳（前 12 个 hex 字符，便于肉眼按时间排序），
  - 80 bit `SecureRandom` 随机数
  组成。高并发下全局唯一且不可预测。
  - 示例：`01a0ed11f997a7bff256904286740e99`
- 唯一性由「时间戳 + 加密级随机数」保证；单测连续生成 10000 个无重复。

### 1.2 入站沿用（与上游系统串联）

- 请求头 `X-Trace-Id` 存在且**严格匹配** `^[0-9a-f]{32}$` 时，沿用该值；
  否则视为非法/缺失，**重新生成**。
- 严格校验是为了防止伪造头进行日志换行注入（log injection）和响应头污染。
- 调用方拿到的标识：
  - 响应头 `X-Trace-Id`（请求一进入就写入，即使后续异常或异步完成也一定存在）；
  - 错误响应体中的 `traceId` 字段；
  - 各演示接口正常响应体中的 `traceId` 字段。

### 1.3 在线程内与日志中的传播

- traceId 同时保存在 `TraceContext`（普通 `ThreadLocal`）和 SLF4J `MDC` 的 `traceId` 键中。
- 日志 pattern 为
  `... [%thread] [traceId=%X{traceId:--}] logger - msg`，
  因此同一请求处理过程中的**所有日志行都带同一个 traceId**；无链路上下文时显示 `-`。

### 1.4 异步 / 多线程传播（重点：防串号、防残留）

本项目**刻意不使用 `InheritableThreadLocal`**——它只在线程「首次创建」时继承，
线程池复用工作线程时会残留上一个任务的值，造成请求串号。

跨线程传播采用显式「快照 → 设置 → 恢复/清理」语义（见 `Tracing.wrap`）：

1. 在**提交任务的请求线程**上快照当前 traceId；
2. 任务在工作线程执行前把快照设入该线程的 `ThreadLocal` + `MDC`；
3. 任务结束 `finally` 中恢复工作线程原有值，原本没有则彻底 `clear()`。

两条异步路径都已覆盖：

- `@Async("traceAwareExecutor")`：`AsyncConfig` 给线程池配置了 `TaskDecorator = Tracing::wrap`，
  业务方法在工作线程里 `TraceContext.getTraceId()` 与日志 MDC 都是请求的 traceId。
- Servlet 异步（返回 `CompletableFuture`）：`TraceFilter`
  - 首次 dispatch 时把 traceId/采样判定存入 request 属性，并注册 `AsyncListener`；
  - ASYNC 重派（发生在另一个 Tomcat 线程）时过滤器恢复同一标识（重写 `shouldNotFilterAsyncDispatch=false`）；
  - 指标在 `AsyncListener` 的 `onComplete/onTimeout/onError` 中记录，用 `AtomicBoolean`
    保证**一次异步请求恰好记录一次**。

### 1.5 清理保证

- 同步请求：过滤器 `finally` 中 `TraceContext.clear()`（同时清 ThreadLocal 与 MDC）。
- 异步请求：请求线程在注册监听器后立即清理；异步业务线程由 `Tracing` 包装清理；
  ASYNC 重派线程在过滤器结束时清理。
- 因此 Tomcat 工作线程与异步线程池线程被复用时，绝不会残留上一个请求的标识。

---

## 2. 指标口径

### 2.1 维度

- 接口键为 `HTTP_METHOD + " " + URI 模板`，例如 `GET /api/demo/order/{id}`。
- URI 模板由 `EndpointPathInterceptor` 在 Spring MVC 完成路径匹配后取得
  （`BEST_MATCHING_PATTERN_ATTRIBUTE`），因此不同路径变量值聚合为同一接口，**避免高基数**。
- 未匹配到处理器的请求退化为 `METHOD + 实际 URI`。

### 2.2 指标项（`/observability/metrics`）

每个接口返回一个快照：

| 字段 | 含义 |
| --- | --- |
| `observedTotal` | **全量**观测请求数，每请求 +1（采样判定之外的全量分母） |
| `sampledTotal` | 采样命中的请求数（细项口径） |
| `successCount` | 成功（2xx/3xx）次数 |
| `paramErrorCount` | 参数错误（HTTP 400，`PARAM_ERROR`）次数 |
| `bizRejectCount` | 业务拒绝（HTTP 409，`BIZ_REJECT`）次数 |
| `systemErrorCount` | 系统异常（5xx，`SYSTEM_ERROR`）次数 |
| `buckets` | 耗时分布各桶计数，桶上界（毫秒）见 `bucketUpperBounds` |
| `bucketUpperBounds` | 固定桶：`10, 50, 100, 200, 500, 1000, 3000, +Inf`（左开右闭，ceil 取整） |

每个采样命中的请求恰好落入一个耗时桶，因此 `sum(buckets) == sampledTotal`。

### 2.3 线程安全：不丢、不重

- 接口→计数器用 `ConcurrentHashMap.computeIfAbsent`，每个接口仅一份计数器；
- 所有计数使用 `LongAdder`（分段累加，高并发无热点竞争），计数不会丢失；
- 每个 HTTP 请求在过滤器（同步）或 `AsyncListener`（异步）的唯一出口**恰好 `record` 一次**，
  不会重复；
- 计数器只增不减、不提供 reset，长时间运行结果稳定可累加。

### 2.4 开关与采样：口径一致、可解释

配置前缀 `observability`（`application.properties`，通过 `ObservabilityProperties` 绑定）：

- `observability.metrics-enabled`（默认 `true`）
  - 关闭期间过滤器**完全跳过指标记录**（`observedTotal` 也不加）；
  - 重新开启后在原有计数上继续累加，**不做任何隐式重置**。
  - 因此「开 → 关 → 开」各区间的口径各自连续：指标值 = 开启期间的累计值，
    与「开启期间的实际流量」严格对应，可解释、可对账。

- `observability.sampling-rate`（默认 `1.0`，取值 `[0,1]`）
  - 采样判定在请求进入时做**一次**（`ThreadLocalRandom`），同一次请求的同步/异步阶段固定不变；
  - **无论是否命中采样，`observedTotal` 始终 +1**，作为全量分母；
  - 仅命中采样的请求才记录耗时桶与成败细项，细项口径明确为「采样命中的请求」；
  - 修改采样率后历史数据不被改写，变更前后各自区间仍可按「分母全量、细项按当时采样率」解释；
  - 非法配置（`<0`、`>1`、`NaN`）会被规整为 `0` 或 `1`，不会污染口径。

> 说明：当前配置随应用启动加载（Spring Boot 标准绑定），修改后重启生效。
> 指标为进程内累计计数，重启进程后从 0 开始（属预期行为）。

### 2.5 状态/异常如何归类

1. `GlobalExceptionHandler` 处理异常时把分类结果写入请求属性，过滤器读取后记录：
   - `BizException(PARAM_ERROR)` → 400；`BizException(BIZ_REJECT)` → 409；
   - `IllegalArgumentException`、参数绑定/反序列化异常 → 400 `PARAM_ERROR`；
   - 其余任何未处理异常 → 500 `SYSTEM_ERROR`。
2. 过滤器兜底：若异常漏出处理器链（例如过滤器层抛出），过滤器记 `SYSTEM_ERROR`
   并直接写脱敏 JSON，保证 traceId 回传与指标都不缺失。
3. 无显式分类时按 HTTP 状态兜底：`>=500` 系统异常；`4xx` 中 409 业务拒绝、其余参数错误；其余成功。
   异步 ASYNC 重派拿不到原 request 属性时也按此规则，不重不漏。

### 2.6 对外错误响应（脱敏）

统一响应体：

```json
{ "traceId": "...", "error": "SYSTEM_ERROR", "message": "服务内部错误，请稍后重试" }
```

- 参数错误 / 业务拒绝的 message 是**我方自定义安全文案**，可以透出；
- 系统异常对外只返回通用文案，**绝不透出** `NullPointerException` 等底层组件原始类型与堆栈；
  原始异常以 ERROR 级别（带 traceId）完整记录在服务端日志，便于按 traceId 排查。

---

## 3. 本地验证方法

### 3.1 运行自动化测试

```bash
mvn test
```

覆盖：

- `TraceIdTest`：标识格式/唯一性、非法入站头拒绝（含换行注入）、跨线程快照传播、线程复用无残留；
- `MetricsTest`：分类计数与固定耗时桶、32 线程 x 5000 次并发计数不丢不重、采样与开关口径、非法采样率规整；
- `ConcurrencyTraceTest`：80 并发全栈请求标识两两不同、响应头/响应体一致、指标按接口聚合；
  合法上游 `X-Trace-Id` 被沿用；
- `AsyncTraceIntegrationTest`：30 个异步请求在仅 2 个工作线程上复用时标识正确且唯一、
  异步指标由 `AsyncListener` 记录；参数错误/业务拒绝/系统异常三类的状态码、脱敏响应与分类计数。

测试日志中每个断言点都打印了请求标识与「判定依据」。

### 3.2 手工验证

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8080
# 或：mvn package -DskipTests && java -jar target/*.jar --server.port=8080
```

| 场景 | 命令 | 预期 |
| --- | --- | --- |
| 正常 | `curl -i localhost:8080/api/demo/ping` | 200，头/体 traceId 一致 |
| 参数错误 | `curl -i localhost:8080/api/demo/order/abc` | 400 `PARAM_ERROR` |
| 业务拒绝 | `curl -i -XPOST localhost:8080/api/demo/order -H 'Content-Type: application/json' -d '{"balance":-1}'` | 409 `BIZ_REJECT` |
| 系统异常脱敏 | `curl -i localhost:8080/api/demo/boom` | 500 通用文案，无 NPE 信息，服务端日志有堆栈 |
| 异步跨线程 | `curl -i localhost:8080/api/demo/async` | 200，工作线程 `trace-async-*` 返回同一 traceId |
| 沿用上游 | `curl -i -H 'X-Trace-Id: cafebabecafebabecafebabecafebabe' localhost:8080/api/demo/ping` | 响应头原样返回该值 |
| 指标 | `curl localhost:8080/observability/metrics` | 各接口计数/耗时桶/成败分类 |

在服务端控制台按某个 traceId 过滤，可看到该请求横跨 Tomcat 线程、异步工作线程、
ASYNC 重派线程的全部日志行都带相同标识：

```bash
grep <traceId> app.log
```

### 3.3 调整开关 / 采样率

修改 `src/main/resources/application.properties` 后重启：

```properties
observability.metrics-enabled=false   # 关闭期间不产生指标
observability.sampling-rate=0.1       # 仅 10% 请求记录细项（observedTotal 仍全量）
```
