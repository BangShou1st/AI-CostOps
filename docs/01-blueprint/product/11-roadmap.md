# 11. 版本路线图 — V1 → V2

## 当前状态

```text
V1 = COMPLETE / FROZEN
Current stable (published) = v1.1.0
V1.1 / M9 = COMPLETE / ACCEPTED (v1.1.0 RELEASED; AIC-074~AIC-083 all PASS)
v1.1.0 = RELEASED
M10 V2 Detailed Design = COMPLETE / FROZEN (AIC-084~AIC-093 PASS)
M10 design merge = PR #129 / main@1ed62c68c09458570c5cd04f812a2525028db7a2
M11 Gateway Edge MVP = COMPLETE / ACCEPTED
M12 Identity / Attribution / Budget Reservation = COMPLETE / ACCEPTED
M13 Realtime Metering / Settlement = COMPLETE / ACCEPTED
M14 Multi-provider Routing / Resilience = COMPLETE / ACCEPTED (PR #146 / main@a9afc8aef64b9d66608ccc19c611b703e545610b)
M15 Hybrid Reconciliation = IMPLEMENTATION IN DELIVERY (feat/m15-hybrid-reconciliation; complete after independent review + acceptance)
M16 V2 Production Acceptance = NEXT AFTER M15 ACCEPTANCE
```

V1 的冻结历史、AIC-001～AIC-073、M0～M8 与最终验收证据继续保留，不改写为当前待办。

V1 → V2 总体设计基线：

```text
docs/superpowers/specs/2026-08-27-v1-to-v2-production-gateway-design.md
```

M10 冻结详细设计与验收：

```text
docs/02-development/v2-detailed-design/README.md
docs/03-acceptance/m10-design-freeze-matrix.md
docs/02-development/api/gateway-openapi.yaml
```

---

# V0 / V0.5 — Research & V1 Detailed Design

已完成并冻结：

```text
Provider research
FOCUS / FinOps baseline
Canonical Domain
Business Invariants
V1 scope
Module boundaries
Data model
State machines
Transactions / idempotency / concurrency
API contract
Permission matrix
Redis contracts
Provider import contracts
Frontend IA
Error / observability design
Git governance
```

---

# V1 — AI Spend Ledger

## 目标

> **把已经花出去的 AI 钱算明白，并提供完整可用的企业后台身份、权限、财务与关账闭环。**

## 当前结果

```text
v1.0.0 RELEASED
v1.0.1 RELEASED
M0–M8 COMPLETE
AIC-001–AIC-073 COMPLETE
Final Human Acceptance = ACCEPTED
P0/P1 = 0
```

## V1 业务范围

```text
IAM / Organization
Evidence
Provider Import
Raw Record / Canonical Cost Facts
Duplicate Review
Allocation
Expense / Approval
Budget / Commitment
Immutable Ledger / Correction
Reconciliation
Billing Period Close / Reopen
Audit
Workbench / Reporting
```

## V1 最终原则

```text
MySQL = Identity + Financial Truth
Redis = Session / TTL / Rate Limit / Cache
MinIO/S3 = Evidence Object
POSTED Ledger = Immutable
Correction = Append-only
Already-incurred cost is never discarded because of insufficient budget
```

V1 不再继续扩产品范围；真实 bug 可按 patch release 修复。

---

# V1.1 / M9 — Production Foundation

## 结果（AIC-083 final acceptance）

```text
ACCEPTED WITH DOCUMENTED NON-BLOCKING LIMITATIONS (see docs/03-acceptance/aic-083-m9-final-acceptance.md)
AIC-074 ~ AIC-082 = PASS; real MiMo certification PASS; import 10k/100k/500k PASS; reporting 10k/100k PASS
v1.1.0 = RELEASED
```

## 目标

> **把已经完成的 V1 财务闭环提升为能够可靠承载 V2 Gateway 的生产工程基础。**

M9 不实现 Realtime Gateway 业务功能。

## Scope

```text
Audit closure
Production configuration hardening
Metrics / Prometheus
Grafana / alerting
Browser E2E automation
Security CI
Backup / restore drills
Real Provider certification path
Import scale benchmark
Read-model performance evidence
Operational / incident runbooks
Release evidence
```

## 关键 DoD

```text
High-value audit gaps closed
Prometheus scrape PASS
Grafana operational dashboard usable
At least one alert failure-injection verified
Critical V1 browser E2E automated
Production profile dev-only configuration fails fast
Security/dependency/container/secret scans produce CI evidence
MySQL restore drill PASS
Evidence restore drill PASS
At least one real-but-sanitized Provider certification completed
10k / 100k / 500k import benchmark reported from real execution
MQ decision based on evidence, not architecture fashion
```

已发布：

```text
v1.1.0
```

Transactional Outbox / RabbitMQ 不是 M9 默认 DoD。先 measure → SQL/index/batch/concurrency tuning；只有证据证明现有 DB-backed worker 不足时再引入。

---

# M10 — V2 Detailed Design

## 结果

```text
M10 = COMPLETE / FROZEN
AIC-084 ~ AIC-093 = PASS / FROZEN
Blocking design topics = 0
AIC-093 DESIGN FREEZE = PASS
Principal design PR = #129
Frozen main baseline = 1ed62c68c09458570c5cd04f812a2525028db7a2
```

M10 是设计里程碑；没有在设计 PR 中偷渡 Gateway runtime feature code。

## 已冻结范围

```text
Control Plane / Data Plane ownership
Gateway credential model
Provider credential policy
Provider / Model catalog
Pricing Version model
Request identity / attribution
Request and route-attempt state machines
Durable DISPATCH_INTENT financial replay fence
MySQL-authoritative Budget Reservation
Redis rate-limit / quota / runtime coordination semantics
Streaming disconnect / timeout semantics
FINAL / INCOMPLETE / UNKNOWN usage normalization
Settlement transaction / idempotency
First-class GATEWAY_SETTLEMENT Ledger lineage
SYSTEM financial posting actor
BillingPeriod dispatch / close serialization
Orphan recovery
Routing / retry / failover safety policy
Security / privacy
Observability
Gateway API contract
Deployment / DB privilege boundary
Migration strategy
Concurrency / failure test strategy
```

关键冻结原则：

```text
MySQL = durable identity + financial truth + monetary reservation correctness
Redis != financial truth and cannot independently authorize spend
Gateway writes request / route / usage / reservation facts
CostOps Core writes final Settlement / Ledger / Budget Actual / Commitment truth
Provider may be called only after durable DISPATCH_INTENT
Unknown post-dispatch retry safety = BILLABLE_POSSIBLE, no blind redispatch
Missing usage != zero cost
Prompt / Completion content is not persisted by default
```

---

# V2 Runtime Architecture

采用：

```text
Monorepo
├─ frontend/   React / TypeScript Admin UI
├─ backend/    Java / Spring MVC Control Plane
└─ gateway/    Java / Spring WebFlux + Reactor Netty Data Plane
```

核心原则：

> **一个 Monorepo、两个 Deployable、一个最终财务 Truth。**

Control Plane 继续持有：

```text
IAM
Organization
Budget
Ledger
Reconciliation
Period Close
Audit
Admin / Reporting
Final Settlement financial posting
```

Gateway Data Plane 新增：

```text
OpenAI-compatible API
Internal Gateway Credentials
Request Identity / Attribution
Rate Limit / Quota
Budget Reservation request-time control
Streaming Proxy
Realtime Metering
Provider Routing
Gateway request / route / usage facts
Gateway Metrics / Audit
```

最终财务 Truth：

```text
MySQL Ledger
```

Budget Reservation correctness：

```text
MySQL authoritative
```

Redis V2：

```text
rate limit
quota window
short idempotency lookup cache
request ephemeral coordination
provider health / circuit state
reservation expiry wake-up hints / non-authoritative cache
```

Redis 不承担 Final Ledger、Final Budget、Final Settlement History，也不能独立决定 monetary availability。

---

# M11 — Gateway Edge MVP

## 状态

```text
NEXT IMPLEMENTATION MILESTONE
```

只建立最小真实链路：

```text
Internal Gateway API Key
→ OpenAI-compatible POST /v1/chat/completions
→ one Provider Adapter (initial candidate: MiMo)
→ non-streaming + SSE streaming
→ response
```

同时完成：

```text
Gateway Java/Spring WebFlux bootstrap
Gateway OpenAPI contract test
request / trace id
provider credential isolation
safe structured logging
connect / header / idle / hard timeouts
basic operational rate limit
controlled mock upstream failure tests
MiMo non-streaming / streaming behavior certification
M10-compatible request / route evidence needed by the M11 slice
```

M11 必须遵守：

```text
required Idempotency-Key on the frozen Gateway API
no blind Provider redispatch after DISPATCH_INTENT
unknown / unsupported client fields rejected
Prompt / Completion not persisted by default
successful response may omit usage when Provider usage is unavailable
missing usage never fabricated as zero
```

M11 不实现完整 M12 Budget Reservation、M13 Settlement 或 M14 multi-provider routing。

---

# M12 — Identity / Attribution / Budget Reservation

请求在进入 Provider 前完成：

```text
organization
credential
user/service identity
project
optional team/cost center
pricing context
budget context
```

预算运行时视图扩展为：

```text
Realtime Available
= Total
- Actual
- Outstanding Commitments
- Effective Active Reservations
```

实现：

```text
MySQL-authoritative reserve / release
route-attempt reservation lineage
TTL as recovery trigger
fencing / recovery
rate limit
quota
Redis atomic runtime coordination where applicable
```

冻结规则：

```text
Redis-only monetary reservation = NOT ALLOWED
Redis loss cannot fabricate Budget availability
```

---

# M13 — Realtime Metering / Settlement

主链：

```text
Provider usage
→ Usage normalization
→ Pricing Version
→ Cost calculation
→ Durable Settlement
→ Existing MySQL Ledger
→ Budget Actual / explicit Commitment effects
```

必须证明：

```text
no duplicate settlement
no duplicate ledger posting
no silent reservation leak
client retry is idempotent
settlement retry is idempotent
partial/missing usage is explicit
Redis failure cannot fabricate budget availability
Period Close cannot race past unresolved possible-billable Gateway work
```

若出现可靠跨事务事件发布需求，优先评估 Transactional Outbox；Outbox 不等于必须引入 RabbitMQ。

---

# M14 — Multi-provider Routing / Resilience

加入：

```text
multiple Provider adapters
model mapping
static routing policy
health-aware routing
circuit breaker
bounded evidence-based retry
safe failover
```

优先：

```text
correct
predictable
explainable
auditable
```

冻结约束：只有上一 attempt 被积极证明为 `SAFE_NO_BILLABLE_EXECUTION` 才允许新的 Provider attempt；未知安全性按 `BILLABLE_POSSIBLE` 处理。不做并行 billable hedging。

---

# M15 — Hybrid Reconciliation

V2 的差异化闭环：

```text
Realtime Gateway Usage
→ Settlement
→ Ledger

Provider Statement Import
→ Canonical Charge
→ Reconciliation
→ Correction / Ledger
```

至少处理：

```text
pricing drift
discount
rounding
provider correction
late charge
missing gateway usage
unknown provider charge
duplicate external charge
```

M11-M13 必须保留 M10 冻结的 request / route attempt / Provider request id / pricing / usage / settlement lineage，不能等到 M15 才补匹配证据。

---

# M16 — V2 Production Acceptance

最终验收必须包括：

```text
load
concurrent streams
client disconnect
provider timeout / outage
Redis outage
MySQL failure / recovery
reservation leak recovery
duplicate requests
settlement retry
credential revoke
budget exhaustion
routing failover
statement difference
```

发布要求：

```text
No lost settlement
No duplicate ledger
No silent reservation leak
No budget overspend caused by race
No Provider secret leak
No prompt/response content leak by default
```

目标发布：

```text
v2.0.0
```

---

# V2.1 / V3 候选

不进入 V2.0 Core：

```text
SAML / SCIM
Full FOCUS conformance
Automatic FX Engine
ERP / GL integration
Advanced approval policy
Cost anomaly detection
Forecast / savings recommendation
Provider contract discount modeling
multi-region
```

按真实业务需求进入后续设计。

---

# V4 — 工程单位经济性（可选）

只有业务 outcome 定义可靠后再考虑：

```text
Cost / successful agent task
Cost / PR
Cost / issue
Cost / eval pass
```

---

# 技术栈守门规则

任何新增基础设施先回答：

1. 解决哪个已观测问题？
2. 现有 MySQL / Redis / DB-backed worker 不能合理解决吗？
3. 能否被 benchmark / failure test 证明？
4. 两人团队能否稳定维护？
5. 是否改善 correctness / recoverability / observability，而不是只增加组件？

默认不加入：

```text
Kafka
Elasticsearch
Kubernetes
service mesh
microservice explosion
custom distributed scheduler
```

RabbitMQ 只有在真实异步吞吐/解耦需求且 DB-backed Outbox Worker 不足时再评估。
