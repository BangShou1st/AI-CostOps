# M12 Identity / Attribution / Budget Reservation — Implementation Plan

> Branch: `feat/m12-identity-budget-reservation` | Baseline: `main@b57d12a`
> Design: `docs/superpowers/specs/2026-09-03-m12-identity-attribution-budget-reservation-design.md`
> 方法：TDD（先失败测试 → 最小实现 → focused 回归 → commit）。不一次性写完再测。

## 提交序列（与任务指令推荐一致）

```text
docs(m12): freeze identity and reservation implementation design   [本计划 + design，已落盘]
feat(m12): add durable budget reservation schema                   [Task 1]
feat(m12): calculate conservative reservation upper bounds         [Task 2]
feat(m12): enforce mysql-authoritative budget admission            [Task 3 = TX1]
feat(m12): gate provider dispatch on durable reservation           [Task 4 = TX2 + controller/quota 接线]
feat(m12): recover reservation holds conservatively                [Task 5 = TTL recovery]
feat(m12): add redis operational request quota                     [Task 6]
feat(m12): block close on unresolved reservation holds             [Task 7]
test(m12): prove reservation concurrency safety                    [Task 8]
test(m12): enforce reservation architecture and telemetry guards   [Task 9]
feat(m12): complete identity attribution and budget reservation    [Task 10 = 最终收尾，可选小修]
docs(m12): record identity and budget reservation evidence         [Task 11]
```

## Task 1 — V19 schema

- 新建 `backend/src/main/resources/db/migration/V19__m12_budget_reservation.sql`（唯一新增迁移，
  不碰 V1–V18）：`budget_reservation` 全列 + `UNIQUE(org_id, route_attempt_id)` +
  generated `effective_slot` + `UNIQUE(org_id, request_id, effective_slot)` +
  金额/状态/version CHECK + 同 org FK。
- Backend 新增 `backend/src/test/java/com/aicostops/gatewayadmin/GatewayM12ReservationSchemaIntegrationTest.java`
 （或归入现有 M11 schema 测试文件旁的新文件）：fresh Testcontainers MySQL 上证明
  prefix/route 唯一、one-effective-hold 唯一、CHECK 约束、同 org FK、精确列类型。
- Gateway `GatewayMySqlContainerSupport.MIGRATIONS` 追加 V19；`GatewayTestFixture.clean()` 追加
  `budget_reservation` 清理（注意 FK 顺序，先删 reservation）。
- 验证：`git diff main...HEAD -- backend/src/main/resources/db/migration` 仅 V19 新增。

## Task 2 — Reservation 上限计算（纯函数，可单测）

- 新建 `gateway/.../budget/ReservationAmountCalculator.java`（纯静态/组件，无 DB/Redis）：
  输入 pricing rates（dimension → unit_price/unit_quantity）+ effective max tokens；
  输出 `reservedAmount`（scale-8 CEILING）或 fail-closed 异常映射到
  `GATEWAY_DEPENDENCY_UNAVAILABLE`（缺 pricing/未知维度/不支持 model）与
  `GATEWAY_BUDGET_EXHAUSTED`（算出 <= 0 的 budget-controlled 请求）。
- 规则：input qty 恒 1_048_576；output qty = effective max tokens（>0 才计 OUTPUT_TOKEN）；
  REQUEST 有则加；CACHED 有则 input 取 max(INPUT, CACHED) 归一化单价；未知维度 fail closed；
  正金额 `setScale(8, CEILING)`。
- 测试：`ReservationAmountCalculatorTest`（单测）：MiMo 基准值、CEILING 不下舍、
  cached 取高、未知维度 fail、<=0 fail、unit_quantity 非 10 幂拒绝。
- 不碰 Controller/DB。

## Task 3 — TX1 MySQL-authoritative admission

- 新增 `gateway/.../budget/BudgetReservationService.java`（`@Transactional` 短事务，
  经 `BlockingIoScheduler` offload）：
  `lockBillingPeriod(OPEN) → resolve exact/ORG Budget（同 currency）→ lock Budget →
  SUM effective holds（同 Budget）→ calculator → enough? insert ACTIVE + VALIDATED→RESERVED
  + persist billing_period_id + current_route_attempt_id : VALIDATED→REJECTED_BUDGET`。
  返回三态：RESERVED(reservationId/amount/budgetId/periodId) / UNBUDGETED(OPTIONAL 无匹配) /
  REJECTED（抛 `GATEWAY_BUDGET_EXHAUSTED`）。
- 新增 `gateway/.../persistence/BudgetReservationMapper.java` + `GatewayReadMapper`
  追加 `findPricingRates(pricingVersionId)`、`selectBudgetIdentity`、`lockBudgetById`、
  `sumEffectiveReservations(budgetId, orgId)`（`status IN (ACTIVE,PENDING_HOLD)`，M13 再扩展）。
  新增 `GatewayRequestMapper` 方法：`markRequestReserved`、`markRequestRejectedBudget`、
  `markRequestFailedPreDispatch`、`updateCurrentRouteAttempt`。
- `GatewayRequestService.authorizeAndFence` 重排：auth/model/route（不变）→ 建 VALIDATED
  request + attempt PLANNED（不变）→ **TX1 reservation admission**（REQUIRED fail-closed
  旧分支删除，改走真实 TX1）→ 返回 admission 结果给 TX2。
- 测试（real MySQL）：`BudgetReservationServiceIntegrationTest`——REQUIRED 无 Budget 拒绝；
  OPTIONAL 无 Budget 放行 unbudgeted；不足拒绝；幂等 replay 不建第二 hold。

## Task 4 — TX2 dispatch fence 门控 + controller 接线

- 改 `DispatchFenceService.commitDispatchFence` 签名/逻辑：入参 admission（RESERVED 时
  reservationId/budgetId/periodId；UNBUDGETED 时 explicit allowed 标记）；
  持 BillingPeriod 锁校验三 matching（route attempt / active Reservation / BillingPeriod），
  否则 `GATEWAY_DEPENDENCY_UNAVAILABLE`（恢复 race 下 fence 失败）；保留 `state IN (...)` 行数判定。
- `ChatCompletionController.handleRequest` 顺序：rate limit → quota（Task 6 提供接口，
  本 task 先接调用点，quota 未启用时直通）→ authorizeAndFence → Provider I/O。
  Quota 拒绝 → `GATEWAY_RATE_LIMITED` 429。
- 测试：`DispatchFenceReservationIntegrationTest`——无 reservation 的 RESERVED 请求 fence 失败；
  UNBUDGETED 显式标记可 fence；错 period/错 attempt fence 失败。

## Task 5 — Reservation TTL recovery（scheduled DB recovery）

- `GatewayProperties` 新增 `reservationTtlMs=900000`、`reservationRecoveryIntervalMs=60000`、
  `reservationRecoveryBatchSize=100` + 启动校验（ttl > hard timeout；三者 positive）；
  `application.yml/local/prod` 暴露 env 覆盖；prod validator 覆盖新键。
- 新增 `gateway/.../budget/ReservationRecoveryService.java`（`@Scheduled` fixedDelay，
  bounded batch）：`SELECT ... FOR UPDATE` 按锁序（BillingPeriod → Budget → Reservation）
  取过期 ACTIVE 行；pre-dispatch（request RESERVED/VALIDATED + attempt PLANNED +
  无 DISPATCH_INTENT）→ RELEASED + request FAILED_PRE_DISPATCH；否则 → PENDING_HOLD；
  version fencing 更新；DISPATCH_INTENT 及之后状态永不 release。
- 测试（real MySQL）：`ReservationRecoveryIntegrationTest`——pre-dispatch 释放；
  post-dispatch 转 PENDING_HOLD；fence race（先 recovery 后 fence 必败；先 fence 后
  recovery 不释放）。

## Task 6 — Redis quota

- 新建 `gateway/src/main/resources/redis/gateway-quota.lua` +
  `gateway/.../quota/GatewayQuotaLimiter.java`（接口）+
  `RedisDailyQuotaLimiter.java`：key `aicostops:v2:gateway:quota:{credentialId}:{yyyyMMddUTC}`，
  ARGV(limit, nowMillis, ttlSeconds)，返回 ALLOWED/REJECTED；启用且 Redis 失败 → 503 fail closed。
- `GatewayProperties` 新增 `quotaEnabled=true/false`（默认 true？M12 取 bounded deploy default：
  默认启用并设 `quotaRequestsPerDay=1000`；dev 可关）、`quotaRequestsPerDay` positive 校验。
- 测试：`RedisQuotaLimiterIntegrationTest`（real Redis：burst/refill-day、并发、Redis-down 503、
  UTC-day key 格式不含 raw key）。

## Task 7 — Close blocker 扩展

- 改 `GatewayCloseBlockerMapper`：新增 `countActiveReservations(orgId, periodId)`
  （`status IN (ACTIVE,PENDING_HOLD)` + join request billing_period_id）；
  改 `GatewayFinancialWorkBlockerProvider`：任一条件（旧 unresolved 请求计数 / 新 reservation
  计数）> 0 即 FAIL；summary 注明双条件。
- Backend 测试：`GatewayFinancialWorkCloseIntegrationTest` 追加——ACTIVE/PENDING_HOLD 阻塞；
  RELEASED/FINALIZED 不单独阻塞。

## Task 8 — 并发证明（real MySQL，跑 5 次）

- 新建 `gateway/.../budget/BudgetReservationConcurrencyIntegrationTest.java`：
  Budget 100 + 80+80 并发 → 恰好一个 reserve；50+50 → 两个都 reserve；
  多并发 SUM 不超容量；V1 Actual 更新（direct `incrementActual`）vs Reservation 同 Budget 串行；
  同幂等 key 并发 → one request/one attempt/one hold/<=one dispatch（Provider spy 零调用计数）。
- 后端/网关现有并发套件回归；记录 5 次结果进 evidence。

## Task 9 — 架构与遥测守护

- `GatewayArchitectureTest` 追加：gateway 不写 ledger/budget actual/commitment/settlement
  相关类（backend 包已禁，补断言 `budget_reservation` 唯一 writer 为 gateway budget 包）；
  新增 `BudgetOwnershipArchitectureTest` 或并入：`com.aicostops.gateway.budget..` 不得依赖
  backend 包；`GatewayMetrics` 新增 reservation/quota 计数方法仅 bounded labels。
- `GatewayRedactionTest` 追加 reservation 金额不泄露到 recovery API（status API 仍不含金额）；
  quota key 无 raw key 断言。
- M13 缺席检查：`git diff --name-only` 扫描 `gateway_usage_fact/gateway_settlement/GATEWAY_SETTLEMENT`
  零出现（测试或脚本断言）。

## Task 10 — 收尾小修（仅归拢，不开新功能）

- Dev bootstrap：pricing rates 追加 REQUEST 行（幂等，M12 计算可选维度需要时可用；
  无 REQUEST 也合法，保持 optional）。
- `GatewayRequestStatusController`：M12 仍返回 `meteringStatus=null/settlementStatus=null`
 （M13 才填），requestState 新增 RESERVED/REJECTED_BUDGET 透出（OpenAPI 已含）。
- `GatewayMetrics`：`reservation_attempt_total{outcome}`、`reservation_recovery_total{outcome}`、
  `quota_total{outcome}`、`reservation_overrun_total`（M12 无 settlement，overrun 计数保留
  接线位，文档注明未触发条件）。

## Task 11 — 证据（`docs/03-acceptance/m12-identity-budget-reservation-evidence.md`）

记录实现 SHA、schema/Backend/Gateway 全量、架构、real MySQL 并发（5 次）、Redis quota
failure injection、MySQL pre-dispatch failure injection、Provider spy 零调用、Close race、
recovery、secret/content 泄漏检查、M13 缺席、migration 不变性、known limitations。
PASS 只记实跑 commit。

## 验证命令（收尾前必跑）

```powershell
Set-Location "E:\project\AI-CostOps\backend"; mvn test
Set-Location "E:\project\AI-CostOps\gateway"; mvn test
Set-Location "E:\project\AI-CostOps"
git diff main...HEAD -- backend/src/main/resources/db/migration   # 仅 V19 新增
git diff --name-only main...HEAD | Select-String "usage_fact|settlement|GATEWAY_SETTLEMENT"  # 零命中
git diff --check; git status; git log --oneline --decorate -15
```
