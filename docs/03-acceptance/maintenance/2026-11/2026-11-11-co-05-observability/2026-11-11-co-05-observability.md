# CO-05 可观测、故障注入与交付验收（2026-11-11）

## 1. 元数据

| 项 | 值 |
|---|---|
| 日期 | 2026-11-11 |
| 责任人 / AI | BangShou1st / Claude Code |
| 关联模块 | Backend / Gateway 可观测性、导入/账本/关账、Gateway 请求生命周期 |
| 关联计划 | `docs/02-development/designs/maintenance/2026-11-10-backend-reliability-refactor-design.md` §8 / §9 CO-P5 / §10 |
| 基线 | `main = dev = 57696e2` |
| 环境 | 真实 MySQL 8.4 + Redis 7 Testcontainers；本地 Spring Boot 4.1 / Java 21.0.10 |
| 结论 | **PASS（附 PARTIAL / NOT_VERIFIED）**——关联标识与核心指标已存在并补充；故障矩阵 7/9 行 PASS，2 行 PARTIAL（真实 MySQL 停机等边界标 `NOT_VERIFIED`） |

## 2. Scope（本 PR 只改这些）

- `TraceIdFilter`：入站 `X-Trace-Id` 有界校验、生成 `trc_` 前缀、写入/清理 MDC。
- `CorrelationWebFilter`：同样的入站校验与 `trc_` 生成，保留 exchange attribute / 响应头。
- 生产 profile 开启 Spring Boot 内置 ECS 结构化日志；非生产保留可读控制台格式并把 `traceId` 追加到相关性字段。
- 导入失败指标：`ImportAttemptExecutor` 在最终化成功后发出 `import.completed{result=FAILED}`，修复告警查询 `result!="SUCCEEDED"` 的死链。
- 对象存储依赖指标/日志：`EvidenceStorageService` 在 STAT/UPLOAD 失败时发 `dependency.error{dependency=OBJECT_STORAGE}` 与一条不含对象键/文件名的 WARN。
- Gateway 指标接线：`reservation_attempt` 在两次 `admitSync` 后计数；Provider 标签集合补齐 `KILO_GATEWAY` / `OPENCODE_ZEN` / `CUSTOM_OPENAI_COMPATIBLE`。
- **不动**：不引入 MQ/Kafka、微服务、OpenTelemetry 后端或新的事件存储；不新增数据库表；不修改财务语义。

## 3. Call-chain 与关联键映射

| 计划书键名 | 实际落点 | 说明 |
|---|---|---|
| `import_batch_id` / `job_id` | `import_batch.id` / `import_attempt.id` | 计划书的 `job_id` 对应 attempt；没有同名列，日志使用 `attemptId` |
| `import_attempt_id` | `import_attempt.id` | 租约、fencing、恢复后继均以它关联 |
| `evidence_id` | `evidence.id` | 上传证据身份；对象键由 org + sha256 派生 |
| `posting_key` | `ledger_posting.posting_key` | 唯一键；Correction 追加时保留 |
| `gateway_request_id` | `gateway_request.public_request_id` | 对外公开 ID；内部 `id` 仅数据库/日志 |
| `idempotency_key` | `gateway_request.idempotency_key_digest` | 只保存 BINARY(32) digest，从不记录原始 key |
| `dispatch_intent_id` | `gateway_route_attempt.id` + `dispatch_intent_at` | 无同名列；TX2 持久化意图的稳定身份 |
| `reservation_id` | `budget_reservation.id` | MySQL 预占行 |
| `provider_request_id` | `gateway_route_attempt.provider_request_id` | Provider 侧请求 ID（可用时） |
| `settlement_id` | `gateway_settlement.id` | 结算行；重复 settle 幂等 |

新增 MDC 字段：`traceId`（后端 Servlet 请求）。Gateway 为 Reactive，`X-Trace-Id` 以 exchange attribute + 响应头传播；日志侧不伪造 MDC。

## 4. 指标与日志

| 信号 | 位置 | 说明 |
|---|---|---|
| `aicostops.login.result` / `import.completed` / `ledger.posting` / `budget.activation` / `reconciliation.*` / `period.close` / `dependency.error` | `AiCostOpsMetrics` | 低基数枚举标签；新增导入 FAILED 与 OBJECT_STORAGE 依赖 |
| `gateway_request_total` / `gateway_provider_error_total` / `gateway_usage_total` / `gateway_reservation_*` / `gateway_quota_total` / `gateway_redis_dependency_error_total` | `GatewayMetrics` | 固定枚举标签；新增 reservation_attempt 接线与 Provider 目录补齐 |
| `traceId` | `TraceIdFilter` + `logging.pattern.correlation` | 非生产日志追加 `[traceId]`；生产 ECS JSON 可查询 |
| ECS 结构化日志 | `application-prod.yml` | `logging.structured.format.console=ecs`；不改变本地开发可读性 |
| 敏感值脱敏 | `SecretShapes` / `IssueSanitizer` / `PayloadRedactor` / `GatewayRedactionTest` | 日志与错误体不写 prompt、raw key、provider secret |

## 5. §8.2 故障注入矩阵

| 行 | 故障 | 结论 | 证据 / 边界 |
|---|---|---|---|
| 1 | MySQL 暂时不可用 | **PARTIAL** | 结算 Worker 的 mapper/discovery 故障与事务回滚有自动测试；真实 MySQL 停机 / API 级连接失败未注入，标 `NOT_VERIFIED` |
| 2 | Redis 暂时不可用 | **PASS** | `RedisTokenBucketRateLimiterFailClosedTest` / `CircuitBreakerRedisIntegrationTest`；预算/账本零 Redis 依赖 |
| 3 | MinIO PUT 成功而 DB 失败 | **PARTIAL** | `EvidenceStorageServiceIntegrationTest` 覆盖 STAGING + 已存在对象的修复；PUT 成功后 markAvailable 失败未单独注入 |
| 4 | DB 成功但 MinIO GET 失败 | **PASS（本轮新增）** | `ImportAttemptExecutorIntegrationTest.unreadableEvidenceObjectFailsAttemptWithoutCanonicalOrLedgerRows`：attempt/batch FAILED、raw_provider_record 0 |
| 5 | Gateway Provider 408/504/断流 | **PASS** | `MimoStreamingIntegrationTest` / `StreamingLifecycleIntegrationTest` / `GatewayUsageFinalizationIntegrationTest`：UNKNOWN/INCOMPLETE + PENDING_HOLD |
| 6 | Worker 认领后崩溃 | **PASS** | `ImportLeaseServiceIntegrationTest` 租约过期 + 后继 + fencing；`ImportWorkerCoordinatorIntegrationTest` |
| 7 | 关账同时提交 Posting | **PASS** | `KnownPeriodCloseRaceIntegrationTest` / `PeriodCloseCoordinatorIntegrationTest` / `ProviderChargePostingIntegrationTest` |
| 8 | 同一 Idempotency-Key 并发 | **PASS** | `GatewayPrepareInitialConcurrencyIntegrationTest`（20 与 50 contenders） / `GatewayRequestIdempotencyIntegrationTest` |
| 9 | 前端依赖 HIGH | **PASS** | Security `trivy` job；CO-00 证据包记录目标级 0 发现 |

## 6. 验证命令

```bash
# 后端关联/指标/导入故障
cd backend && ./mvnw -B verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=ImportAttemptExecutorIntegrationTest,EvidenceStorageServiceIntegrationTest,ProblemDetailAdviceTest \
  -Dfailsafe.failIfNoSpecifiedTests=false

# Gateway 指标/请求关联
cd gateway && ./mvnw -B test -Dtest=GatewayMetricsTest,RedisTokenBucketRateLimiterTimeoutTest \
  -DfailIfNoSpecifiedTests=false

# 文档链接
npm run verify:links
```

本轮实测：`ProblemDetailAdviceTest` 7/7（含合法入站 traceId 保留与非法值替换）、`ImportAttemptExecutorIntegrationTest` 20/20（含对象不可读失败与 FAILED 指标 +1）、`EvidenceStorageServiceIntegrationTest` 12/12；Gateway `RedisTokenBucketRateLimiterTimeoutTest` 1/1。

## 7. Decision

- **不引入** RabbitMQ/Kafka/微服务/OpenTelemetry 后端：现有 Micrometer + Actuator + Spring Boot 4.1 结构化日志足以覆盖本计划的关联与故障信号；新增事件存储会扩大运维面且无性能证据支持。
- 关联键以数据库/API 现有的稳定业务键为准；`job_id`、`dispatch_intent_id`、`gateway_request_id` 三个计划书键名没有同名列，已在上表映射，不新增列/迁移。
- 导入失败告警保持现有 `result!="SUCCEEDED"` 表达式；本轮补齐 FAILED 发射点，使告警从死链变为可触发。
- 未接线的 `circuit_transition` / `provider_usage_parse_error` 等指标在本轮不强行接线；已在 CO-03 限制中登记，避免留下“看起来有监控”的假信号。

## 8. 限制

- 真实 MySQL 进程停机、真实 Provider 端到端为 `NOT_VERIFIED`；不以 Testcontainers 结论冒充生产高可用。
- Gateway 为 Reactive 栈，`MDC` 不保证跨 Reactor 线程传播；当前以响应头/exchange attribute 为关联契约，未引入分布式追踪后端。
- 结构化日志只在生产 profile 启用；本地开发保持可读格式。
