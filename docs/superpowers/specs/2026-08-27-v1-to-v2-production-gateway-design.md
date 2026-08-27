# AI-CostOps V1 → V2 Production & Realtime Gateway Design Baseline

- 日期：2026-08-27
- 基线：`main@5b82d97e72e5a863b15dd7164dd0b744ca1454f7`
- 当前稳定版本：`v1.0.1`
- 文档状态：**V2 规划候选基线，等待 Human Review**
- 适用范围：V1.1 Production Foundation + V2 Realtime AI Gateway

## 1. 决策摘要

AI-CostOps V1 已完成并冻结。V1 的核心价值是把已经发生的多 Provider AI 成本变成可信、可归属、可审批、可对账、可关账的内部成本账。

V2 不重写 V1，也不更换核心后端语言。正式方向为：

```text
V1.0.1
  ↓
V1.1 / M9 Production Foundation
  ↓
M10 V2 Detailed Design
  ↓
M11 Gateway Edge MVP
  ↓
M12 Identity / Attribution / Budget Reservation
  ↓
M13 Realtime Metering / Settlement
  ↓
M14 Multi-provider Routing / Resilience
  ↓
M15 Hybrid Reconciliation
  ↓
M16 Production Acceptance
  ↓
v2.0.0
```

技术路线：

```text
Frontend / Admin UI
= React + TypeScript

Control Plane
= 现有 Java / Spring MVC Backend

Realtime Data Plane
= 新增 Java / Spring WebFlux + Reactor Netty Gateway

Financial Truth
= MySQL Ledger

Realtime / Ephemeral State
= Redis

Evidence
= S3-compatible Object Storage
```

核心架构原则：

> **一个 Monorepo、两个 Deployable、一个最终财务 Truth。**

不采用“直接把 Gateway 塞入现有 MVC Backend”，也不采用“立即拆成微服务/Kafka/Kubernetes”的路线。

---

## 2. V1 已有资产

V2 必须建立在 V1 已验证能力之上，不重新实现已有领域。

### 2.1 身份与权限

V1 已有：

```text
Registration / Invitation
Login
Access JWT
Refresh Session Rotation
Logout / Logout-all
Password Reset
Account Disable
Role / Permission
ORG / PROJECT / TEAM / COST_CENTER Data Scope
Security Version
Redis Authorization Cache
```

这些能力继续由 Control Plane 持有。Gateway 只消费经过明确契约暴露的身份、凭证与授权结果，不复制 IAM 数据模型。

### 2.2 财务核心

V1 已有：

```text
Budget
Budget Commitment
Allocation
Expense Approval
Immutable Ledger
Correction
Reconciliation
Billing Period Close / Reopen
Audit
```

V2 的 Realtime Settlement 最终必须进入现有财务模型，而不是创建第二套账。

### 2.3 Provider 与成本事实

V1 已有：

```text
Evidence
Import Batch / Attempt
Provider Adapter
Raw Provider Record
Canonical Facts
Charge Fact
Attribution Hint
Duplicate Review
```

V2 Gateway 的 realtime usage 是新的事实来源，但最终需要与 statement import 共存并在 M15 进行 Hybrid Reconciliation。

### 2.4 工程资产

V1 已有：

```text
Java 21
Spring Boot
Spring Security
Plain MyBatis
Flyway
MySQL 8.4
Redis
MinIO / S3-compatible storage
React / TypeScript
Docker Compose
GitHub Actions
JUnit / Testcontainers
ArchUnit
ProblemDetail
Trace/Audit 基础
```

V2 优先复用这些工程约束；新增技术必须有明确问题、真实测量和维护收益。

---

## 3. V1 → V2 差距分类

差距分为四类：技术债、生产化缺口、规模证据缺口、V2 新领域缺口。

### 3.1 非阻塞技术债

以下不是 V1 release blocker，但进入 V2 前应收口：

1. Provider Account create/update/archive 缺少完整 Audit producer。
2. Allocation Rule version publish/archive 缺少完整 Audit producer。
3. Logout / Session Revoked / Password Changed 等平台事件存在 `org_id = NULL` 的查询可见性问题。
4. Project / Team / Cost Center 主数据 CRUD 的 Audit 覆盖不完整。
5. Reconciliation / Close 部分 Audit 证据为 flow-level，逐事件 assertion 强度不足。
6. Backend 与 Frontend 的项目版本号未与 GitHub Release 形成一致的版本发布策略。
7. 设计文档中的部分 Production/Observability 结构尚未落地。

### 3.2 Production Readiness 缺口

V1 Compose 是可靠的本地开发、Smoke 与验收环境，但不能直接等价为生产部署方案。

V1.1/M9 必须建立：

```text
Production profile hardening
TLS / HTTPS ingress boundary
Production secret policy
Backup / Restore
RPO / RTO baseline
Metrics
Prometheus
Grafana
Alerting
Structured operational logs
Browser E2E automation
Security scanning in CI
Container scanning
Release artifact / image promotion
Rollback runbook
Real mail integration path
Provider certification process
Retention / archival baseline
Incident runbook
```

多副本、HA、Kubernetes、multi-region 不自动进入 M9；只有部署目标真实要求时再设计。

### 3.3 Scale Evidence 缺口

V1 Import benchmark 最大实测仅到 1,024 input rows，不能外推为 100k/500k/1m 生产容量。

M9 必须按阶段建立真实证据：

```text
10k
→ 100k
→ 500k
```

并记录：

```text
wall-clock latency
records/sec
heap
GC
CPU
DB CPU / IO
SQL / statement counts
batch persistence latency
worker concurrency behavior
object-storage impact
```

若 500k 在现有设计下不能合理完成，再根据证据选择优化方案。

优先顺序：

```text
measure
→ SQL / index / batch tuning
→ bounded concurrency tuning
→ transactional outbox if needed
→ message broker only if justified
```

不得因为“企业系统应该有 MQ”而默认引入 RabbitMQ/Kafka。

### 3.4 V2 新领域缺口

V1 没有以下 realtime 能力：

```text
Internal Gateway Credential
OpenAI-compatible API
Streaming Proxy
Request Identity
Request Attribution
Provider / Model Catalog
Versioned Pricing
Realtime Rate Limit / Quota
Budget Reservation
Realtime Metering
Request State
Settlement
Provider Routing
Circuit Breaker
Failover
Realtime Cost Dashboard
Gateway vs Statement Reconciliation
```

这些属于 V2 正式新增领域，不能伪装成 V1 小修补。

---

## 4. V1.1 / M9 — Production Foundation

### 4.1 目标

把 V1 从“功能闭环且已发布”提升为“可以承载后续 Gateway 的生产工程基础”。

M9 不新增 Realtime Gateway 业务功能。

### 4.2 M9 必做范围

#### Audit Closure

完成至少：

```text
Provider Account create/update/archive audit
Allocation Rule publish/archive audit
Platform auth audit visibility decision
Critical reconciliation/close direct assertions
```

#### Observability

落地：

```text
Micrometer
Prometheus registry
HTTP metrics
Auth metrics
Import metrics
Financial metrics
Reconciliation / Close metrics
Redis dependency metrics
JVM metrics
DB pool metrics
```

提供 Grafana 基础 Dashboard，并给关键 failure signal 配置可验证 Alert。

M9 不承诺未经实测的 Production SLO。

#### Browser E2E

为 V1 关键流程增加真实浏览器自动化。最低覆盖：

```text
Login / Session
Import Review
Allocation
Budget / Commitment
Expense
Ledger / Correction
Reconciliation
Period Close / Reopen
Permission negative paths
```

浏览器自动化不能替代 Integration Test；两者职责不同。

#### Production Configuration

生产配置必须满足：

```text
Dev bootstrap default OFF
Public registration default OFF
No default production credential
JWT signing key required
Redis auth required when production policy demands it
Object storage credentials required
Secure refresh cookie
Explicit allowed origins
No file-backed mailbox in production
Fail-fast on invalid production configuration
```

#### Security CI

CI 增加：

```text
dependency vulnerability scan
static security scan
container image scan
secret scanning gate
```

SBOM / provenance 可在 M9 同步建立；若工具链成本明显高，可作为 v1.1 后续补丁，但不得影响核心 security gate。

#### Backup / Restore

必须用真实演练证明：

```text
MySQL backup
→ destroy test environment
→ restore
→ financial truth checks PASS

Evidence backup / restore
→ object availability checks PASS
```

Redis 不承担 Financial Truth，因此 Redis restore 不作为账务恢复前置条件。

#### Provider Certification

建立至少一个“真实但脱敏”的 Provider 导入认证流程；不得把 synthetic fixture 说成生产认证。

Provider 认证需记录：

```text
observed schema
parser version
sample period
field mapping
amount reconciliation
known omissions
redaction process
```

#### Performance Evidence

至少完成：

```text
10k import
100k import
500k import
```

如果 500k 需要超过合理开发环境资源，应记录资源限制与瓶颈，而不是伪造“PASS”。

Workbench / 月度聚合要建立更大规模的 read-model benchmark；具体规模按 fixture 生成能力和真实数据库行为确定，但不得直接沿用未实测的 1m 宣称。

### 4.3 M9 DoD

M9 只有同时满足以下条件才算完成：

1. Audit follow-up 中的高价值 gap 已关闭或有明确接受记录。
2. Prometheus metrics 可抓取。
3. Grafana 至少有一套可用 operational dashboard。
4. 至少一个 Alert 经过故障注入验证触发与恢复。
5. V1 关键浏览器流程有自动 E2E。
6. Production profile 对 dev-only 配置 fail-fast。
7. Security scan 成为 CI 证据的一部分。
8. MySQL restore drill PASS。
9. Evidence restore drill PASS。
10. 至少一个真实 Provider 导入认证完成。
11. 10k/100k/500k Import benchmark 有真实报告。
12. 根据 benchmark 决定 DB-backed worker 是否继续保持，不能提前决定 MQ。
13. README / PROJECT_CONTEXT / roadmap 与 v1.1 实际能力一致。

---

## 5. V2 架构：Control Plane + Data Plane

### 5.1 选择方案

采用：

```text
Monorepo
├─ frontend/   Admin UI
├─ backend/    Control Plane
└─ gateway/    Realtime Data Plane
```

`backend` 与 `gateway` 是两个独立可部署 Spring Boot application。

### 5.2 Control Plane 职责

现有 Backend 继续负责：

```text
IAM
Organization
Projects / Teams / Cost Centers
Provider Account metadata
Gateway Credential administration
Provider / Model catalog administration
Pricing administration
Budget administration
Ledger
Reconciliation
Period Close
Audit query
Reporting / Admin Workbench
```

Control Plane 使用现有 Spring MVC / transaction-first 模型。

### 5.3 Gateway Data Plane 职责

新 Gateway 负责：

```text
OpenAI-compatible API
Internal credential authentication
Request identity
Request attribution
Rate limit / quota
Budget reservation
Provider selection
Streaming proxy
Realtime usage capture
Metering
Settlement orchestration
Realtime request audit / metrics
```

Gateway 不拥有最终 Ledger，不允许在 Redis 中创建第二套财务 Truth。

### 5.4 为什么 Gateway 独立 Deployable

Control Plane 与 Gateway workload 不同：

```text
Control Plane:
short HTTP requests
transaction-heavy
admin / finance workflow
DB correctness first

Gateway:
long-lived streams
high concurrency
provider network latency
backpressure / disconnect
low overhead request path
```

因此采用独立 Runtime Boundary，而不是为了技术展示拆微服务。

---

## 6. V2 Java 技术路线

V2 继续以 Java 为核心后端语言。

### 6.1 Control Plane

```text
Java 21+
Spring Boot
Spring MVC
Spring Security
Plain MyBatis
Flyway
MySQL
```

保持与 V1 一致。

### 6.2 Gateway

候选固定方向：

```text
Java
Spring Boot
Spring WebFlux
Reactor Netty
Spring Security
Redis
Resilience4j or equivalent bounded resilience primitives
Micrometer
Prometheus
```

具体 Java/Spring Boot/WebFlux/Resilience4j 版本在 M10 实施前重新核验官方稳定版本后冻结；设计文档不凭当前记忆硬编码未来版本。

### 6.3 不选择 Go / Rust / Node 作为主 Gateway 语言

不是因为这些语言不能做 Gateway，而是因为当前项目需要复用：

```text
Money semantics
Budget invariants
IAM model
Audit model
ProblemDetail / error conventions
Testcontainers
Observability conventions
Java engineering ownership
```

引入第二后端语言的收益不足以抵消双栈维护成本。

### 6.4 Shared Code 守门

初始结构保持：

```text
/backend
/gateway
/frontend
```

只有在存在两个真实消费者且语义稳定后，才允许抽出窄 shared module，例如：

```text
money primitive
wire contract
problem/error identifiers
```

禁止一开始建立万能：

```text
common
core
foundation
platform
utils
```

---

## 7. 数据真相与一致性原则

### 7.1 MySQL

继续作为：

```text
Identity durable truth
Budget durable truth
Ledger final truth
Settlement durable result
Billing period truth
Audit durable truth
```

### 7.2 Redis

V2 可扩展为：

```text
Gateway credential short cache
Rate limit
Quota window
Short idempotency window
Budget reservation
Request ephemeral state
Provider health / short circuit state
```

Redis failure policy必须逐功能定义。

Redis 不得成为：

```text
Final Ledger
Final Budget
Final Period State
Final Settlement History
```

### 7.3 S3-compatible Storage

继续用于 Evidence，不自动把 Prompt / Response Body 存入对象存储。

Prompt / Response 内容默认不属于 CostOps 财务证据，除非未来有独立合规设计和明确 opt-in。

---

## 8. Gateway 核心领域边界

M10 详细设计至少需要形成以下边界。

### 8.1 Gateway Credential

负责：

```text
Internal API key issue
hash-only storage
scope
owner
project binding
status
expiry
rotate
revoke
last-used metadata
```

原始 key 仅创建时返回一次，持久化只保存不可逆 digest 与识别前缀。

### 8.2 Provider Credential

Provider secret 不能下发到客户端。

必须支持：

```text
encrypted / external secret reference
rotation
provider account binding
audit
redaction
```

是否由应用层加密存 DB 或引用外部 Secret Manager，在 M10 根据部署目标决策；不能明文持久化。

### 8.3 Provider / Model Catalog

负责：

```text
provider
model
provider model name
capability
status
routing eligibility
pricing version reference
```

模型显示名与 Provider 实际 model id 分离。

### 8.4 Request Identity / Attribution

每个可计费 Gateway Request 在进入 Provider 前，必须能够绑定：

```text
organization
credential
user or service identity
project
optional team / cost center
provider/model decision
```

无法满足必需归属策略的请求，不应先发送到 Provider 再事后猜归属。

### 8.5 Budget Reservation

Realtime request 前进行有界 Reservation。

预算模型从：

```text
Available = Total - Actual - Outstanding Commitments
```

扩展为运行时视图：

```text
Realtime Available
= Total
- Actual
- Outstanding Commitments
- Active Reservations
```

最终账务仍以 MySQL Actual/Commitment 为准；Reservation 是短生命周期控制状态。

### 8.6 Metering

负责：

```text
prompt tokens
completion tokens
cached tokens if provider exposes them
request count
provider usage metadata
pricing version
estimated cost
final calculated cost
```

不同 Provider 的 usage 语义通过明确 Adapter/Normalizer 处理，禁止猜公式。

### 8.7 Settlement

负责：

```text
reservation finalization
usage finalization
financial event creation
idempotent ledger integration
orphan recovery
retry
```

Settlement 必须可重试且不会产生 duplicate Ledger。

### 8.8 Routing

M14 才进入完整多 Provider Routing。

初期原则：

```text
static policy first
health-aware second
cost-aware / complex policy later
```

Routing 必须可解释、可审计、可重放关键决策输入。

---

## 9. Gateway Request 状态机

M10 详细设计必须以状态机而不是散落 boolean 实现。

基础主链：

```text
RECEIVED
  ↓
AUTHENTICATED
  ↓
ATTRIBUTED
  ↓
RESERVED
  ↓
UPSTREAM_STARTED
  ↓
STREAMING / RESPONSE_RECEIVED
  ↓
USAGE_FINALIZED
  ↓
SETTLED
```

失败/取消分支至少包括：

```text
REJECTED_AUTH
REJECTED_SCOPE
REJECTED_RATE_LIMIT
REJECTED_BUDGET
UPSTREAM_FAILED
CLIENT_CANCELED
TIMEOUT
RESERVATION_EXPIRED
SETTLEMENT_PENDING
SETTLEMENT_FAILED
RELEASED
```

不要求每一个状态都对应一张数据库表或长期持久化；状态持久化边界在 M10 设计中按恢复需求决定。

---

## 10. Reserve → Settle → Release 不变量

这是 V2 最关键的财务控制链。

### 10.1 Reserve

发送 Provider 请求前：

```text
identity validated
scope validated
pricing context resolved
budget context resolved
reservation amount calculated
atomic reservation succeeds
```

才允许进入 upstream。

### 10.2 Settle

Provider 使用完成且 usage 可计算后：

```text
reservation
→ actual usage
→ price version
→ calculated amount
→ durable settlement
→ ledger integration
```

Settlement 必须有 business idempotency key。

### 10.3 Release

以下情况释放未使用 Reservation：

```text
Provider reject before billable usage
request canceled before billable usage
timeout with confirmed no billable usage
reservation TTL expires and recovery confirms no settlement
reserved > final actual difference
```

若是否产生上游计费不确定，不能简单 Release；进入 reconciliation/recovery 状态。

### 10.4 核心不变量

必须自动测试：

1. 同一个 Gateway Request 不产生两次最终 Settlement。
2. 同一个 Settlement 不产生重复 Ledger posting。
3. Reservation 不因客户端重试重复占用。
4. 任何失败路径最终是 Settled、Released 或显式 Pending，不允许静默泄漏。
5. Redis 故障不能伪造预算充足。
6. MySQL/Settlement 写入失败不能对客户端宣称财务已完成。
7. Closed Billing Period 的 realtime posting 策略必须由明确规则决定，不能绕过 Period Guard。

---

## 11. Streaming 语义

Gateway 必须把 streaming 当一等场景。

最低设计问题：

```text
SSE framing
backpressure
client disconnect
provider disconnect
partial tokens
final usage chunk
missing final usage
idle timeout
hard timeout
cancel propagation
connection resource limit
```

默认不记录完整 Prompt / Completion body 到普通 log、audit 或 metrics。

如果 Provider 在断线前已产生 billable usage，但 final usage 不可获得，不能直接记 0；必须进入可追踪的 incomplete metering / reconciliation 路径。

---

## 12. Provider Resilience

Gateway resilience 必须是有界的。

### Retry

只对明确安全、幂等或未进入 billable execution 的 transient failure 重试。

禁止对所有 5xx/timeout 无脑 retry。

### Timeout

区分：

```text
connect timeout
response-header timeout
stream idle timeout
request hard deadline
```

### Circuit Breaker

用于隔离持续失败 Provider；状态不是财务 Truth。

### Failover

只有在以下条件明确时才允许自动 fallback：

```text
请求尚未产生上游不可逆计费
模型语义允许替代
数据/隐私策略允许
预算/价格重新计算完成
routing policy 允许
```

不能因为第一个 Provider 响应慢就把同一用户请求同时发给多个收费 Provider，除非未来明确设计 Hedging 成本语义。

---

## 13. Hybrid Reconciliation

V2 的差异化核心不是“又一个 OpenAI Proxy”，而是 realtime 与 post-billing truth 合并。

目标链：

```text
Gateway Request
→ Realtime Usage
→ Settlement
→ Ledger

Provider Statement Import
→ Canonical Charge
→ Reconciliation
→ Ledger / Correction
```

M15 必须能够识别至少：

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

Realtime Settlement 不等于 Provider 最终 invoice truth；最终需要通过既有 Reconciliation/Correction 体系收口差异。

---

## 14. Roadmap 与里程碑

### M9 — V1.1 Production Foundation

范围见第 4 节。

目标发布：`v1.1.0`。

### M10 — V2 Detailed Design

必须冻结：

```text
module/runtime boundaries
data model
request state machine
credential model
provider/model catalog
pricing version model
identity/attribution contract
reservation algorithm
Redis Lua/atomicity contract
metering semantics
settlement transaction/idempotency
streaming failure semantics
routing policy
security/privacy
observability
API contract
deployment boundary
migration strategy
test strategy
```

M10 完成前不开始大规模 Gateway feature coding。

### M11 — Gateway Edge MVP

仅建立最小端到端 Gateway：

```text
Internal API Key
OpenAI-compatible endpoint
one Provider
non-streaming
streaming
request/trace id
safe timeout
safe logging
basic rate limit
```

不做五 Provider routing。

### M12 — Identity / Attribution / Budget Reservation

加入：

```text
organization/user/service identity
project attribution
quota
budget reservation
reserve/release
TTL recovery
Redis atomicity
```

### M13 — Realtime Metering / Settlement

加入：

```text
usage normalization
pricing version
cost calculation
settlement
ledger integration
idempotency
orphan recovery
```

如果这里出现可靠跨事务事件发布需求，优先评估 Transactional Outbox。

Outbox 不等于必须引入 RabbitMQ。

### M14 — Multi-provider Routing / Resilience

加入：

```text
multiple Provider adapters
model mapping
routing policy
provider health
circuit breaker
bounded retry
safe failover
```

### M15 — Hybrid Reconciliation

把 Gateway Settlement 与 Provider Statement Import 对齐。

### M16 — Production Acceptance

必须验证：

```text
load
concurrent streams
client disconnect
provider timeout/outage
Redis outage
MySQL failure/recovery
reservation leak recovery
duplicate requests
settlement retry
credential revoke
budget exhaustion
routing failover
statement difference
```

发布目标：`v2.0.0`。

---

## 15. V2 测试策略

V2 继续坚持“证据优先”。

### Unit

覆盖：

```text
pricing
reservation amount
state transitions
routing policy
usage normalization
error mapping
```

### Integration

使用真实 Testcontainers：

```text
MySQL
Redis
```

Provider 使用可控 mock upstream server，支持：

```text
normal JSON
SSE
slow response
partial stream
disconnect
429
5xx
timeout
malformed usage
```

### Concurrency

至少：

```text
same credential concurrent requests
same budget concurrent reservations
idempotent duplicate request
reservation settle/release races
orphan recovery races
```

### E2E

验证：

```text
client
→ Gateway
→ mock/real sandbox Provider
→ metering
→ settlement
→ ledger
→ admin workbench
```

### Load / Soak

M16 前建立可复现 load harness。

指标必须记录真实环境，不能把本地数字宣传成 Production TPS。

### Failure Injection

沿用 V1 M8 经验，把 Redis/MySQL/upstream/client disconnect 当正式验收场景。

---

## 16. Security 与隐私

V2 新增的数据面扩大了安全面。

必须满足：

```text
Internal Gateway Key hash-only
Provider Key never exposed to client
Authorization header redacted
No raw secret in audit
No prompt/response body in ordinary logs
Org/project data scope enforced
Credential rotate/revoke auditable
Rate limit cannot fail open when policy requires fail closed
Settlement authorization server-side only
```

Prompt/Completion 内容默认不持久化。

如果未来产品需要 prompt observability，必须另行做数据分类、retention、encryption、access control 和用户告知设计，不作为 V2 Core 的隐式副作用。

---

## 17. Observability

Gateway 最低指标：

```text
request count
active streams
request latency
time to first token
stream duration
provider latency
provider 429 / 5xx
timeout
client cancel
rate-limit reject
budget reject
reservation active/released/expired
metering incomplete
settlement success/failure/retry
routing decision count
circuit state
Redis latency/error
DB pool / transaction metrics
```

关键日志使用 structured fields：

```text
trace_id
request_id
org_id
credential_id
project_id
provider
model
route_decision_id
reservation_id
settlement_id
```

禁止把 prompt、completion、Authorization、API Key 等放进上述字段。

---

## 18. Deployment 方向

V2 先保持 Docker-first。

目标拓扑：

```text
TLS / Ingress
  ├─ Frontend / Control Plane
  └─ Gateway

Backend → MySQL / Redis / S3
Gateway → Redis / MySQL contract / Provider upstreams
```

具体 Gateway 是否直接写部分 MySQL settlement tables，还是通过窄 Control Plane internal API/port 进行 durable settlement，在 M10 通过 transaction boundary、failure recovery、latency 与 ownership 决定。

默认优先减少分布式事务，不为了“服务解耦”制造双写。

Kubernetes 不是 V2 前置条件。

---

## 19. 技术栈守门规则

新增基础设施必须回答：

1. 解决哪个已观测问题？
2. MySQL / Redis / DB-backed worker 不能合理解决吗？
3. 是否有 benchmark/failure evidence？
4. 两人团队能否稳定维护？
5. 引入后是否改善正确性、恢复性或可观测性，而不是只增加组件？

默认不加入：

```text
Kafka
Elasticsearch
Kubernetes
service mesh
microservice explosion
custom distributed scheduler
```

RabbitMQ 只在有真实异步吞吐/解耦需求且 DB-backed Outbox Worker 不足时评估。

---

## 20. V2 Core 明确不做

以下不进入 V2.0 Core：

```text
SAML / SCIM
Full FOCUS conformance
Automatic FX Engine
Full ERP / GL
Bank payment
Tax automation
Custom Approval DSL
Prompt management product
RAG
Agent workbench
Model quality evaluation platform
Multi-region active-active
Kubernetes migration for its own sake
Cost per PR / issue / agent outcome
```

这些可作为 V2.1/V3/V4 候选，根据真实用户需求再进入设计。

---

## 21. 风险与控制

### R-V2-01 Gateway Scope Creep

风险：Gateway MVP 同时做五家 Provider、复杂路由、全部企业功能。

控制：M11 只允许 one-provider edge MVP。

### R-V2-02 Redis Becoming Financial Truth

风险：实时 reservation 逐渐替代 MySQL 账务真相。

控制：所有最终 Settlement/Ledger 必须 durable；Redis 只承担可恢复短状态。

### R-V2-03 Duplicate Billing

风险：client retry、Gateway retry、Settlement retry 导致重复 Ledger。

控制：request idempotency + settlement business uniqueness + DB constraint + concurrency tests。

### R-V2-04 Reservation Leak

风险：进程崩溃/断线后 reservation 永久占用。

控制：TTL + owner/fencing/recovery + explicit pending states + leak metrics。

### R-V2-05 Incorrect Retry / Failover

风险：上游已计费后重复发送。

控制：retry/failover 只允许在可证明安全的阶段执行。

### R-V2-06 Prompt Privacy Leak

风险：为了 observability 把 prompt/response 写日志。

控制：默认内容不落日志/审计；未来内容可观测性单独设计。

### R-V2-07 Premature Infrastructure

风险：先引入 MQ/K8s/Kafka，再找问题。

控制：技术守门规则 + benchmark evidence。

### R-V2-08 Production Claim Without Evidence

风险：将 Compose、synthetic provider 或本地 load 数字宣传成 Production capacity。

控制：沿用 V1 Frozen Evidence Policy；所有性能/认证声明必须对应真实报告。

---

## 22. 版本策略

建议：

```text
v1.0.1   current stable
v1.1.0   Production Foundation
v2.0.0-alpha.x   Gateway development checkpoints if release artifacts are useful
v2.0.0-beta.x    Multi-provider + hybrid reconciliation acceptance
v2.0.0   V2 production acceptance complete
```

是否实际发布 alpha/beta GitHub Release 由开发节奏决定；内部 Milestone 不依赖必须打预发布 tag。

---

## 23. 文档与计划治理

本文件经 Human Review 接受后：

1. 更新 `docs/01-blueprint/product/11-roadmap.md`，使旧 V1.5/V2 路线与本基线一致。
2. 更新 `PROJECT_CONTEXT.md`，声明当前进入 M9/V1.1。
3. 为 M9 单独生成详细 Implementation Plan。
4. 按实施计划创建新的 AIC stable IDs / GitHub Issues，不一次性创建 M9–M16 全部 Issue。
5. M9 完成并验收后，再执行 M10 V2 Detailed Design；不得把本总纲当成 Gateway 的最终 Data Model/API Spec。
6. M10 的详细设计通过 Human Review 后，再生成 M11+ 的实施计划。

这样保持：

```text
Design Baseline
→ Detailed Plan
→ Small Issues
→ PR + CI
→ Evidence
→ Human Acceptance
```

与 V1 已经证明有效的协作方式一致。

---

## 24. 当前结论

项目当前阶段定义为：

```text
V1 = COMPLETE / FROZEN
Current stable = v1.0.1
V1.1 / M9 = READY FOR IMPLEMENTATION PLANNING AFTER THIS SPEC IS APPROVED
V2 Gateway = ARCHITECTURE DIRECTION APPROVED IN PRINCIPLE, DETAILED DESIGN NOT YET FROZEN
```

最终产品演进目标保持：

```text
Post-billing Provider Imports ----\
                                   >---- MySQL Cost Ledger ---- Governance
Realtime AI Gateway -------------/
```

V1 负责“把钱算清楚”。

V2 负责“在钱花出去之前控制它、在花费发生时计量它、在账单到达后重新核对它”。
