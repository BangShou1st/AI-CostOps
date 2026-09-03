# M12 Identity / Attribution / Budget Reservation — Implementation Design (Frozen)

> Date: 2026-09-03 | Branch: `feat/m12-identity-budget-reservation` | Baseline: `main@b57d12a`
> Authority: M10 freeze (`docs/03-acceptance/m10-design-freeze-matrix.md`, AIC-084~AIC-093).
> 本文档冻结 M12 实现语义；与 M10 冲突时以 M10 为准并 reopen 对应 AIC。

## 1. 目标与非目标

实现（M12 切片）：

```text
Gateway credential 治理的 identity/attribution
MySQL-authoritative Budget Reservation（TX1）
Dispatch fence 门控（TX2，复用 BillingPeriod 锁）
Reservation TTL + scheduled DB recovery
Redis operational quota（per credential / UTC day，非金钱）
Close blocker 扩展（ACTIVE / PENDING_HOLD 阻塞）
```

不实现（M13+，即使顺手也不做）：

```text
gateway_usage_fact / gateway_usage_dimension / gateway_settlement
GATEWAY_SETTLEMENT Ledger source / SYSTEM posting / Actual posting
Commitment consumption / Settlement retry / finalized cost accounting
M14 failover / M15 reconciliation / 通用 policy DSL / credential admin 产品
```

## 2. Identity / Attribution（冻结）

Canonical principals：`HUMAN_MEMBER`、`SERVICE`（禁止发明 `MEMBER`）。

Gateway credential 永久治理（request client 不可覆盖）：

```text
organization / principal / project / financial scope / budget enforcement mode / allowed models
```

- SERVICE 不可动态选择另一个 Project；不同 governed Project 需要不同 credential。
- Financial target 恰好其一：`PROJECT` / `TEAM` / `COST_CENTER`。
- M11 已有 auth 链（prefix/digest、principal/project/scope active、explicit model allowlist、
  Provider Account/Credential、Pricing Version）保持不动；M12 只新增 budget enforcement 语义：
  REQUIRED 在 M11 是 fail-closed，现在走真实 Reservation；OPTIONAL 无匹配 Budget 可 unbudgeted，
  有匹配 Budget 则必须 reserve，exhausted 则 reject。

## 3. Budget lookup（冻结，与 V1 `LedgerBudgetService` 一致）

```text
financial scope + BillingPeriod + pricing currency
→ ORG + same BillingPeriod + same currency
→ no Budget
```

禁止 `TEAM → PROJECT → ORG` / `COST_CENTER → PROJECT → ORG` 隐式 Project fallback。

## 4. Budget modes（冻结）

REQUIRED：无匹配 Budget / 不足 / unsafe upper bound → Provider 前失败。
OPTIONAL：无匹配 Budget → 允许 unbudgeted（`budget_reservation_id = NULL`）；
有匹配 Budget → 必须 reserve；exhausted → reject。OPTIONAL 不是 bypass。

## 5. FX（冻结）

无 FX。Pricing currency 必须等于 Budget lookup currency；
不同-currency Budget 等价于无匹配 Budget。

## 6. Monetary truth（冻结）

```text
Realtime Available = Budget.total_amount - Budget.actual_amount
  - Budget.committed_amount - SUM(effective ACTIVE/PENDING_HOLD reservations)
```

- `budget.committed_amount` 是 V1 权威 outstanding Commitment 计数器，M12 不独立重算 Commitments。
- Redis 永不参与该货币计算。
- M12 无 Settlement，故 effective 规则简化为 `status IN (ACTIVE,PENDING_HOLD)` 同 Budget 行；
  M13 再接入 `gateway_settlement.status = SETTLED` 排除规则（SQL 预留注释，不实现 Settlement 读取）。

## 7. Reservation amount（冻结，MiMo `mimo-v2.5-pro`）

```text
context token ceiling = 1_048_576
output token ceiling  = 131_072
Input Reservation quantity = 1_048_576（不用 chars/token 估计）
Output Reservation quantity = Gateway 已校验并实际发往上游的 exact effective max_completion_tokens
```

Pricing dimensions：`INPUT_TOKEN`（required）、`OUTPUT_TOKEN`（positive output limit 时 required）、
`REQUEST`（optional）、`CACHED_INPUT_TOKEN`（optional）；unknown dimension → fail closed。

- 存在 CACHED_INPUT_TOKEN 时，对全部保守 input token 取 `INPUT_TOKEN` 与 `CACHED_INPUT_TOKEN`
  的更高 normalized unit rate；不预测 cache hit。
- 仅 BigDecimal；正金额 `reservedAmount = raw.setScale(8, CEILING)`，永不向下舍入。
- Reservation `<= 0` / 非 DECIMAL(20,8) 可表示 / 缺 pricing / 不支持 model → budget-controlled 请求 fail closed。

`normalized unit rate = unit_price / unit_quantity`；`unit_quantity` 须为 10 的幂且 `<= 1_000_000_000`
（沿用 AIC-092 应用层校验）。

## 8. V19 schema（冻结，`V19__m12_budget_reservation.sql`，不改 V1–V18）

`budget_reservation`（Gateway-owned，MySQL-authoritative spend hold）：

```text
id PK / org_id / request_id / route_attempt_id / billing_period_id / budget_id
financial_scope_type / financial_scope_id / currency CHAR(3)
reserved_amount DECIMAL(20,8) / commitment_id NULL / commitment_backed_amount DECIMAL(20,8) DEFAULT 0
status / version BIGINT / expires_at / created_at / updated_at / released_at NULL / finalized_at NULL
```

约束：

```text
UNIQUE(org_id, route_attempt_id)
effective_slot = CASE WHEN status IN ('ACTIVE','PENDING_HOLD') THEN 1 ELSE NULL END
UNIQUE(org_id, request_id, effective_slot)
reserved_amount > 0
0 <= commitment_backed_amount <= reserved_amount
status IN (ACTIVE, PENDING_HOLD, RELEASED, FINALIZED)
version >= 0
同 org FK：request / route_attempt / billing_period / budget
```

- M12 Commitment 字段恒为 `commitment_id = NULL`、`commitment_backed_amount = 0`
  （显式绑定是未来治理扩展位，不在 M12 推断/消费任何 Commitment）。
- `FINALIZED` 仅 schema/lifecycle 兼容 M13；M12 不产生 Settlement，不写 FINALIZED（除 recovery 不碰）。

## 9. Reservation transaction TX1（冻结）

短同步 MySQL 事务，Reactor Netty offload（`BlockingIoScheduler`）。Canonical 顺序：

```text
BillingPeriod FOR UPDATE → selected Budget FOR UPDATE
→ effective Reservation rows → insert/replay Reservation → gateway_request state
```

流程：

```text
lock OPEN BillingPeriod
resolve exact Budget or ORG fallback（同 currency，无 FX）
lock Budget
calculate total - actual - committed - active holds（同 Budget 行，同事务）
if enough: insert ACTIVE Reservation；VALIDATED → RESERVED；
  persist billing_period_id；persist current_route_attempt_id
if insufficient: VALIDATED → REJECTED_BUDGET
commit
```

- 不合并 monetary reservation 与 Provider network I/O；Budget 锁持有期间不调 Provider。
- 幂等 replay：同 `(org, route_attempt)` 唯一收敛；同 request 已有 effective hold 则不建第二 hold
  （`UNIQUE(org, request, effective_slot)` 兜底；冲突转 state conflict / 复用）。
- REJECTED_BUDGET 幂等语义沿用 M11：同 key 复放同 terminal error。

## 10. Dispatch fence TX2（冻结，保持两事务分离）

TX1（Reservation → request RESERVED → COMMIT）与 TX2（BillingPeriod lock → verify ACTIVE
Reservation → request DISPATCH_INTENT → route attempt DISPATCH_INTENT → COMMIT）分离；
durable gap 由 Reservation recovery 处理，不为省代码合并。

`DispatchFenceService`（修改，不替换 financial fence 架构）：持 BillingPeriod 锁时，
budget-controlled RESERVED 请求必须同时满足：matching route attempt、matching active
Reservation、matching BillingPeriod。OPTIONAL/no-Budget 路径仅当 reservation admission
显式分类为 allowed unbudgeted 才可 `VALIDATED → DISPATCH_INTENT`；任意 VALIDATED 不得绕过 M12。

并发同幂等 identity：至多一次 state transition 获胜，至多一次 Provider dispatch
（`markRequestDispatchIntent` 的 `state IN (...)` 条件更新 + 影响行数判定保持）。

## 11. Recovery（冻结）

Bounded Reservation TTL + scheduled DB recovery。默认：

```text
reservationTtlMs=900000（> gateway hard timeout 600000，启动校验）
reservationRecoveryIntervalMs=60000
reservationRecoveryBatchSize=100
```

TTL 含义是 investigate this hold，不是 release this money。

Recovery 事务锁序：`BillingPeriod → Budget → Reservation`。

- 确定 pre-dispatch（request RESERVED/VALIDATED + route attempt PLANNED + 无 DISPATCH_INTENT 证据）：
  `ACTIVE → RELEASED`，request → `FAILED_PRE_DISPATCH`。
- 可能已 dispatch：`ACTIVE → PENDING_HOLD`。
- 永不 release：`DISPATCH_INTENT / UPSTREAM_ACTIVE / TRANSPORT_COMPLETED /
  CANCELED_AFTER_DISPATCH / TIMED_OUT_AFTER_DISPATCH / FAILED_AFTER_DISPATCH /
  BILLABLE_POSSIBLE attempt`。
- 版本 fencing：`UPDATE ... SET status=?, version=version+1 WHERE id=? AND version=?
  AND status IN (...)`；stale recovery 不可释放新 owner/state。

Recovery/fence race（必须显式测试）：fence 先拿 BillingPeriod 锁 → DISPATCH_INTENT 获胜，
Reservation 保持经济持有；safe recovery 先拿锁 → RELEASED + FAILED_PRE_DISPATCH，
fence 后续必须失败。禁止 `DISPATCH_INTENT + RELEASED` 共存。

## 12. Redis quota（冻结，非金钱 operational quota）

最小契约：per credential / UTC day 的 request count。Key：

```text
aicostops:v2:gateway:quota:{credentialId}:{yyyyMMddUTC}
```

永不放 raw credential/API key。配置：`quotaEnabled`、`quotaRequestsPerDay`
（M12 bounded global deploy-time default，不做通用 policy DSL / credential admin 产品）。

Atomic Lua：increment + 首键设 expiry + compare limit → ALLOWED / REJECTED。
启用时 Redis 失败 → fail closed（503 前置于 Provider I/O）。
Quota 拒绝复用 `GATEWAY_RATE_LIMITED` / HTTP 429，不新增 public error code。
Redis 丢失只影响 operational quota 历史，永不影响 MySQL Reservation 货币授权。

执行顺序（Controller）：rate limit → quota → authorizeAndFence（含 TX1+TX2）→ Provider I/O。

## 13. Close blocker（冻结，不新增 enum）

复用 `PENDING_GATEWAY_FINANCIAL_WORK`。Block 条件新增：存在 `ACTIVE` / `PENDING_HOLD`
budget_reservation（同 period；join request 的 billing_period_id）。`RELEASED` / `FINALIZED`
不单独阻塞。M11 既有 unresolved possible-billable request 条件保留。

## 14. API / Errors / Ownership（冻结）

- 不向 Chat Completion request 加 `project_id/team_id/cost_center_id/budget_id/currency/
  pricing_version_id/provider_id`；不经 frozen Gateway recovery API 暴露 Budget total/
  available / Reservation amount / Ledger detail。
- 复用 `RESERVED` / `REJECTED_BUDGET` 状态。
- 错误码复用：`GATEWAY_BUDGET_EXHAUSTED`（429：insufficient / REQUIRED 无匹配）、
  `GATEWAY_RATE_LIMITED`（429 quota）、`GATEWAY_DEPENDENCY_UNAVAILABLE`（503：
  pricing/bound 不可安全评估、Reservation 前 MySQL 不可用、mandatory quota Redis 不可用，
  零 Provider 调用）、`GATEWAY_IDEMPOTENCY_CONFLICT`、`GATEWAY_REQUEST_IN_PROGRESS`。
  禁止发明 `GATEWAY_BUDGET_EXCEEDED`。
- Gateway MAY：读 credential/catalog/pricing；read/lock BillingPeriod；read/lock Budget；
  写 gateway_request / gateway_route_attempt / budget_reservation。
  Gateway MUST NOT：update actual/committed；写 commitment_usage / Ledger /
  gateway_settlement；close/reopen period；跑 Flyway；写 admin catalog/credential truth。
  （DB 权限 + ArchUnit 双重守护。）

## 15. 日志 / metrics（冻结）

永不 log/store：Prompt / Completion / Authorization / raw Gateway key / raw Idempotency-Key /
Provider secret / HMAC key / KEK。Prometheus labels 不含 org/request/credential/budget/
member/service id；仅 bounded outcome/reason/status。新增：

```text
reservation_attempt_total{outcome}
reservation_active（gauge，可用 count 查询代替则文档注明）
reservation_pending_hold
reservation_overrun_total
reservation_recovery_total{outcome}
quota_total{outcome}
redis_dependency_error_total{operation_class}（沿用 gateway_redis_dependency_error_total 命名时注明映射）
```

## 16. 并发测试（冻结，real MySQL/Testcontainers，非 mock-only）

```text
Budget 100 + 80+80 并发 → 恰好一个 reserve
Budget 100 + 50+50 → 两个都 reserve
多并发 holds → SUM effective holds 永不超容量
V1 Budget Actual 更新 vs Reservation → 同 Budget 行锁串行
同幂等 key 并发 → one request / one route attempt / one effective hold / <= one dispatch
recovery vs dispatch fence → 永不 DISPATCH_INTENT + RELEASED
```

金融并发套件至少跑 5 次。
