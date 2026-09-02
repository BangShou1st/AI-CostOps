# 11. 版本路线图 — V1 → V2

## 当前状态

```text
V1 = COMPLETE / FROZEN
Current stable (published) = v1.0.1
V1.1 / M9 = COMPLETE / ACCEPTED (v1.1.0 release candidate; AIC-074~AIC-083 all PASS)
v1.1.0 = PENDING RELEASE PR MERGE + FINAL MAIN GREEN (not yet published/tagged)
M10 V2 Detailed Design = NEXT DESIGN MILESTONE
V2 Gateway = ARCHITECTURE DIRECTION APPROVED; M10 DETAILED DESIGN REQUIRED BEFORE FEATURE CODING
```

V1 的冻结历史、AIC-001～AIC-073、M0～M8 与最终验收证据继续保留，不改写为当前待办。

V1 → V2 总体设计基线：

```text
docs/superpowers/specs/2026-08-27-v1-to-v2-production-gateway-design.md
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
v1.1.0 = PENDING RELEASE PR MERGE + FINAL MAIN GREEN (not yet published/tagged)
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

目标发布：

```text
v1.1.0
```

Transactional Outbox / RabbitMQ 不是 M9 默认 DoD。先 measure → SQL/index/batch/concurrency tuning；只有证据证明现有 DB-backed worker 不足时再引入。

---

# M10 — V2 Detailed Design

## 目标

> **冻结 Realtime AI Gateway 的领域、状态机、数据模型、API、Redis 原子协议和失败恢复语义。**

M10 仍是设计里程碑，不进行大规模 Gateway feature coding。

必须冻结：

```text
Control Plane / Data Plane boundary
Gateway credential model
Provider credential policy
Provider / Model catalog
Pricing version model
Request identity / attribution
Request state machine
Budget reservation algorithm
Redis Lua / atomicity contract
Rate limit / quota semantics
Streaming disconnect semantics
Usage normalization
Settlement transaction / idempotency
Orphan recovery
Routing policy
Security / privacy
Observability
API contract
Deployment boundary
Migration strategy
Test strategy
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
```

Gateway Data Plane 新增：

```text
OpenAI-compatible API
Internal Gateway Credentials
Request Identity / Attribution
Rate Limit / Quota
Budget Reservation
Streaming Proxy
Realtime Metering
Provider Routing
Settlement
Gateway Metrics / Audit
```

最终财务 Truth：

```text
MySQL Ledger
```

Redis V2：

```text
rate limit
quota window
short idempotency window
budget reservation
request ephemeral state
provider health / circuit state
```

Redis 不承担 Final Ledger、Final Budget、Final Settlement History。

---

# M11 — Gateway Edge MVP

只建立最小真实链路：

```text
Internal API Key
→ OpenAI-compatible endpoint
→ one Provider
→ non-streaming + SSE streaming
→ response
```

同时完成：

```text
request / trace id
provider credential isolation
safe structured logging
connect / header / idle / hard timeouts
basic rate limit
controlled mock upstream failure tests
```

M11 不做五 Provider routing。

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
- Active Reservations
```

实现：

```text
reserve
release
TTL
fencing / recovery
rate limit
quota
Redis atomicity
```

财务 durable truth 仍在 MySQL。

---

# M13 — Realtime Metering / Settlement

主链：

```text
Provider usage
→ Usage normalization
→ Pricing version
→ Cost calculation
→ Reservation finalization
→ Durable settlement
→ Existing MySQL Ledger
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
bounded retry
safe failover
```

优先：

```text
correct
predictable
explainable
auditable
```

不优先做复杂“智能路由”。

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
