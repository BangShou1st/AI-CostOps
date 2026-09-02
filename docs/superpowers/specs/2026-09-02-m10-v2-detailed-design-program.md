# AI-CostOps M10 — V2 Detailed Design Program

- 日期：2026-09-02
- 仓库：`BangShou1st/AI-CostOps`
- 远端基线：`main@a144210c7110aa2b924b5ef5393686ba329537bd`
- V1 Release commit：`102f287da9bfc922ffaabb1b7244a973a0f813eb`
- V1 stable tag：`v1.1.0`（必须继续固定到上述 release commit）
- 本地仓库路径：`E:\project\AI-CostOps`
- 设计分支：`docs/m10-v2-detailed-design`
- 状态：M10 DESIGN PROGRAM — REVIEW CANDIDATE

---

## 1. 目标

M10 的目标不是实现 Gateway，而是冻结 M11～M15 所依赖的领域、状态机、数据模型、API、Redis 原子协议、失败恢复语义和财务边界，使后续实现不再依赖聊天上下文或开发者临场猜测。

```text
M10 PASS
= implementation ambiguity removed
= blocking design decisions frozen
= M11 implementation readiness proven

M10 PASS
!= Gateway implemented
```

M10 完成前禁止 broad Gateway feature coding。

---

## 2. 继承基线

M10 必须继承并保持以下 V1/V1.1 不变量：

```text
MySQL = identity + durable financial truth
Redis != financial truth
POSTED Ledger = immutable
Correction = append-only
already-incurred provider cost must not disappear
```

预算：

```text
Available
= Total
- Actual
- Outstanding Commitments
```

V2 runtime 视图：

```text
Realtime Available
= Total
- Actual
- Outstanding Commitments
- Active Reservations
```

已发生的 Provider cost 即使遇到 budget exhaustion、reservation failure、routing failure、Redis failure、MySQL transient failure 或 client disconnect，也不得被当成零成本或静默丢弃；无法实时精确结算时必须进入显式 incomplete/pending/reconciliation 路径，并最终由 durable settlement 或 Provider statement reconciliation 收口。

---

## 3. 已批准的 Runtime Direction

采用：

```text
Monorepo
├─ frontend/   React / TypeScript Admin UI
├─ backend/    Java / Spring MVC Control Plane
└─ gateway/    Java / Spring WebFlux + Reactor Netty Data Plane
```

核心原则：

```text
one monorepo
two deployables
one final financial truth
```

Control Plane 继续拥有：

```text
IAM
Organization
Projects / Teams / Cost Centers
Budget
Ledger
Reconciliation
Period Close
Audit
Admin / Reporting
Gateway credential administration
Provider/model/pricing administration
Final Settlement / Financial Posting
```

Gateway Data Plane 负责运行时链路：

```text
OpenAI-compatible edge
Internal Gateway credential authentication
Request identity / attribution
Rate limit / quota
Budget reservation
Provider selection
Streaming proxy
Realtime usage capture
Metering
Durable usage fact production
Settlement initiation/orchestration
Gateway runtime metrics / audit
```

Gateway 不拥有第二套 Ledger，不允许 Redis 演化为 Budget/Settlement/Ledger final truth。

---

## 4. M10 Scope Guard

### 4.1 允许修改

M10 分支只允许设计与规划类改动：

```text
docs/**
PROJECT_CONTEXT.md         only when milestone state is finalized
README.md                  only when milestone state is finalized
```

### 4.2 禁止修改

M10 设计分支禁止加入：

```text
gateway application code
backend business code
frontend feature code
Flyway production migration
Maven/Node runtime dependency change
Docker runtime service addition
Gateway endpoint implementation
Redis Lua production script
Provider adapter production implementation
```

需要验证技术可行性的内容只能定义为可复现实验要求或后续 M11+ implementation task，不得在 M10 偷渡生产功能代码。

---

## 5. Delivery Strategy

M10 采用一个纯文档分支和一个 principal PR：

```text
branch: docs/m10-v2-detailed-design
base:   main@a144210c7110aa2b924b5ef5393686ba329537bd
```

M10 内部按 AIC 稳定编号形成语义 commit 边界。小团队不为高度耦合的设计文档创建十个长期并行分支。

推荐 commit 边界：

```text
docs(m10): freeze scope and runtime ownership
docs(m10): define credentials catalog and pricing
docs(m10): define request identity and state machine
docs(m10): define reservation and redis atomicity
docs(m10): define provider streaming and metering
docs(m10): define settlement financial boundary
docs(m10): define routing and resilience semantics
docs(m10): define security observability and deployment
docs(m10): consolidate data api migration and testing contracts
docs(m10): close final design freeze
```

最终允许 squash merge，但 PR review 必须能看见完整设计演进。

---

## 6. Stable IDs

M10 使用：

```text
AIC-084 ~ AIC-093
```

### AIC-084 — V2 Scope & Runtime Boundary

冻结：

```text
V2 product scope
Control Plane ownership
Data Plane ownership
Provider Adapter ownership
MySQL read/write ownership
runtime/deployment boundary
shared-code guard
M11/M12/M13/M14/M15 responsibility split
Gateway technology compatibility baseline
```

必须明确：

```text
what belongs to Gateway
what belongs to CostOps Core
what belongs to Provider Adapter
what is durable
what is ephemeral
what may enter financial truth
what is telemetry only
```

#### Durable ownership decision

M10 采用同一 MySQL 内的窄 DB-backed handoff，不引入 MQ，也不要求 Gateway 在请求链路中通过同步 HTTP 调用 Backend 才能保存已发生 usage：

```text
Gateway single-writer durable facts:
- gateway_request
- gateway_usage_fact

Backend / CostOps Core single-writer financial results:
- gateway_settlement
- existing ledger / budget actual / commitment usage
```

规则：

1. `gateway_usage_fact` 是 Provider usage/metering 的 durable input fact；FINAL usage fact 在写入后不可被 destructive rewrite，只能追加 correction/reconciliation lineage。
2. Backend settlement worker 从 durable usage facts 发现未结算项目，通过业务唯一键创建 `gateway_settlement`，并在既有 CostOps financial transaction boundary 内完成 Ledger posting、Budget actual、Commitment 与 BillingPeriod guard。
3. Gateway 不直接写 Ledger、Budget actual 或 Commitment usage，也不复制现有 financial posting rules。
4. Backend 不修改 Gateway request/usage 的业务事实；它只读取这些 durable facts 并写自己的 settlement/financial truth。
5. Correctness 不依赖 MQ。未来若需要 wake-up signal，可以增加可丢失通知，但通知不能成为唯一工作事实；DB-backed discovery/recovery 仍必须成立。
6. Gateway 在发送 Provider 请求前必须能够建立必要的 durable request identity；MySQL 在 upstream 尚未开始时不可用时默认 fail closed。Upstream 已开始后发生 MySQL 故障时，禁止把可能已发生的 cost 记零，必须进入 incomplete/reconciliation 可见路径。

该边界避免：

```text
Gateway direct ledger mutation
Backend/Gateway double ownership of settlement truth
sync Backend HTTP becoming billable-usage durability prerequisite
premature MQ
```

Gateway 技术栈继续使用 Java 21 + Spring Boot/WebFlux/Reactor Netty 方向；AIC-084 的详细设计必须基于当前 `main` 与官方兼容矩阵记录具体版本选择，不得凭旧记忆升级或新建第二套 Java baseline。

DoD：不存在 Backend 与 Gateway 对同一 durable business state 的双重 writer ownership。

### AIC-085 — Credential / Provider / Model / Pricing Model

冻结：

```text
Internal Gateway Credential
Provider Credential
Provider Account binding
Provider Catalog
Model Catalog
Provider Model ID
Capabilities
Routing eligibility
Pricing Version
Effective interval
Currency
Pricing dimensions
credential rotation/revocation
```

硬规则：

```text
Gateway key raw value returned once
Gateway key persisted hash-only + identifier prefix
Provider secret never exposed to client
Provider secret never stored plaintext
credential rotation/revoke auditable
model display identity separated from provider model id
pricing version immutable after effective use
```

DoD：给定一个合法 request，可以解析出唯一 attribution context、model context 和 pricing context。

### AIC-086 — Request Identity / Attribution / Request State Machine

冻结 request identity：

```text
organization
credential
user or service identity
project
optional team
optional cost center
requested model
resolved provider/model
request_id
trace_id
client idempotency key
```

Request 主状态链负责 Data Plane 生命周期，不把最终财务 Settlement 状态塞进同一个持久化枚举：

```text
RECEIVED
→ AUTHENTICATED
→ ATTRIBUTED
→ RESERVED
→ UPSTREAM_STARTED
→ STREAMING / RESPONSE_RECEIVED
→ USAGE_FINALIZED
```

失败/异常状态至少包含：

```text
REJECTED_AUTH
REJECTED_SCOPE
REJECTED_RATE_LIMIT
REJECTED_BUDGET
UPSTREAM_FAILED
CLIENT_CANCELED
TIMEOUT
RESERVATION_EXPIRED
METERING_INCOMPLETE
METERING_UNKNOWN
```

Settlement 由 AIC-089 单独状态机承载。整体业务可以观察为 `Request + Usage + Settlement` 联合状态，但不得依赖一个跨 deployable、双方都修改的万能 request status 字段。

每个 Request 状态必须定义：

```text
legal predecessor
legal successor
durable or ephemeral
billable possibility
retry eligibility
recovery action
```

DoD：request lifecycle 不依赖散落 boolean，也不产生跨 deployable 双写状态。

### AIC-087 — Budget Reservation / Rate Limit / Redis Atomicity

冻结：

```text
reservation identity
budget scope
currency
reserved amount
request owner
TTL
fencing token
reserve
release
finalize
expire
recovery
quota
rate limit
Redis outage policy
```

原子协议必须明确输入、输出和失败类型；不得只写“使用 Lua 保证原子性”。

核心不变量：

```text
same request does not reserve twice
concurrent requests cannot overspend through race
release cannot release another owner's reservation
stale worker cannot finalize/release after fencing loss
Redis failure cannot fabricate available budget
reservation leak becomes explicit recoverable state
```

Reservation 的 TTL 不能等价为“过期即确认无成本并释放”。Recovery 必须结合 durable request/usage/settlement 状态判断：已存在可能 billable usage 时保持保守占用或转换为显式 pending hold，直到 settlement/reconciliation 给出可释放结论。

DoD：所有 reservation race 和 expiry path 都有 deterministic outcome 和 recovery path。

### AIC-088 — Provider Adapter / Streaming / Metering

Provider Adapter 负责封装 provider-specific wire semantics，不负责 Financial Posting。

冻结：

```text
provider request translation
provider response translation
non-streaming
SSE framing
backpressure
client disconnect
provider disconnect
connect timeout
header timeout
idle timeout
hard deadline
usage normalization
cached token semantics
missing final usage
malformed usage
provider request identifier capture
```

Metering 结果必须为三类之一：

```text
FINAL
INCOMPLETE
UNKNOWN
```

禁止因为 final usage chunk 缺失、client disconnect 或 provider disconnect 就默认为 zero cost。

Durable usage fact 至少保留：

```text
request_id
provider/model identity
provider request id when available
normalized usage dimensions
metering status
pricing context reference
timestamps
raw provider usage metadata only when classified safe and bounded
```

Prompt/Completion body 默认不进入 usage fact。

DoD：每种 upstream termination path 都能得到显式 meter state；无法精确 meter 的已发生请求仍可进入后续 reconciliation。

### AIC-089 — Settlement / Financial Posting Boundary

Settlement 属于 CostOps Core 的 durable financial result，Gateway 负责产生 settlement input，而不是直接改账。

冻结：

```text
settlement business key
usage_fact lineage
pricing version snapshot
calculated cost
reservation finalization
financial posting integration
transaction boundary
idempotency
orphan recovery
retry
BillingPeriod policy
settlement state machine
```

Settlement 状态至少：

```text
PENDING
PROCESSING
SETTLED
RETRYABLE_FAILED
RECONCILIATION_REQUIRED
```

`SETTLED` 是 durable final result；失败 attempt 不能删除已发生 usage fact。

核心不变量：

```text
one FINAL usage fact -> at most one final Settlement
one Settlement -> at most one Ledger posting
client retry != duplicate usage fact / settlement
settlement retry != duplicate ledger
no settlement row does not mean zero cost
no silent reservation leak
```

Financial Posting 决策：

```text
Gateway writes durable request/usage facts.
Backend DB-backed settlement worker owns final settlement.
Existing CostOps financial domain owns Ledger posting,
Budget actual mutation, commitment semantics and BillingPeriod guard.
```

Settlement 与 Ledger posting、Budget actual/Commitment mutation 在可行时使用同一 MySQL transaction boundary；Redis reservation release 不作为该 MySQL transaction 成功的前置条件。MySQL commit 后 Redis release/finalization 失败必须可通过 settled durable state 重试恢复。

Closed BillingPeriod 不允许被 Gateway 或 settlement worker 绕过；若 usage 已发生但目标 period 无法正常 posting，进入 `RECONCILIATION_REQUIRED` 或等价明确状态，而不是丢弃 cost。

DoD：Settlement crash/retry 可以安全恢复，不产生 duplicate Ledger；Redis 后处理失败不会回滚或伪造已提交的财务 truth。

### AIC-090 — Routing / Retry / Resilience Semantics

M14 才实现完整 multi-provider routing；M10 先冻结语义。

路由演进顺序：

```text
static policy
→ health-aware
→ more complex/cost-aware only with evidence
```

冻结：

```text
route decision inputs
route_decision_id
provider/model eligibility
provider health state
bounded retry
circuit breaker
safe failover
billable boundary
model substitution policy
privacy/region eligibility
```

禁止：

```text
retry every 5xx/timeout
blind failover after possible billable execution
parallel hedging to multiple billable providers by default
```

DoD：每一个 retry/failover rule 都标明是否可能已经产生 upstream billable side effect。

### AIC-091 — Security / Privacy / Audit / Observability / Deployment

冻结：

```text
Gateway key hashing
Provider secret isolation
Authorization redaction
Prompt/Completion default non-persistence
Audit metadata whitelist
structured log fields
metric cardinality policy
trace/request correlation
credential revoke propagation
TLS/Ingress boundary
dependency failure visibility
production configuration boundary
```

默认禁止普通 log/audit/metric 包含：

```text
prompt
completion
Authorization
Gateway raw API key
Provider raw API key
secret material
```

Dependency failure policy 至少明确：

```text
Redis unavailable before upstream -> budget/rate policy fail closed as configured
MySQL unavailable before durable request establishment -> fail closed before upstream
MySQL unavailable after possible billable execution -> explicit incomplete/pending signal + alert + reconciliation path
Provider failure after possible billable execution -> never blind retry
```

DoD：安全、隐私与 dependency failure 不依赖开发者人工记忆；contract 提供 deny-by-default/fail-safe 约束。

### AIC-092 — Data Model / API / Migration / Test Contract

整合前面全部设计，冻结真正可编码的 contract。

目标文档目录：

```text
docs/02-development/v2-detailed-design/
├─ README.md
├─ 01-scope-runtime-boundary.md
├─ 02-credentials-catalog-pricing.md
├─ 03-request-state-machine.md
├─ 04-budget-redis-atomicity.md
├─ 05-provider-streaming-metering.md
├─ 06-settlement-financial-boundary.md
├─ 07-routing-resilience.md
├─ 08-security-observability-deployment.md
└─ 09-data-api-migration-testing.md
```

API governance：

```text
Control Plane HTTP contract
→ existing docs/02-development/api/openapi.yaml remains machine-readable source of truth

Gateway OpenAI-compatible Data Plane contract
→ docs/02-development/api/gateway-openapi.yaml
```

同时更新 API README，明确两个 contract 的 authority boundary，禁止相互重复定义同一 endpoint。

原因：Control Plane `/api/v1` 与 Gateway OpenAI-compatible edge 属于不同 deployable 和不同 compatibility surface；强塞同一 YAML 会让 server/auth/versioning 语义混杂。业务不变量仍以 Detailed Design 为上位约束。

Migration strategy：

```text
forward-only Flyway
no rewrite of V1 migrations
new durable Gateway facts and Settlement remain in same MySQL system of record
single-writer table ownership documented
Redis keys are recoverable runtime state and use explicit version namespace
```

测试矩阵必须覆盖：

```text
unit
MySQL integration
Redis integration
concurrency
mock upstream
SSE
client disconnect
provider disconnect
429/5xx/timeout
Redis outage
MySQL failure before upstream
MySQL failure after upstream started
MySQL recovery
idempotent duplicate request
reservation settle/release race
orphan usage recovery
settlement retry
post-commit Redis release failure
credential revoke
budget exhaustion
closed BillingPeriod
```

DoD：实现者不需要聊天记录即可开始 M11。

### AIC-093 — Final M10 Design Freeze

创建：

```text
docs/03-acceptance/m10-design-freeze-matrix.md
```

每个 required topic 使用且只使用：

```text
FROZEN
DEFERRED WITH EXPLICIT BOUNDARY
BLOCKED
```

Acceptance 文档不得出现未决占位项；任何仍需实现阶段临场决定、且会改变 correctness/API/data ownership 的事项，都必须判定为 `BLOCKED`，不能伪装成 freeze。

AIC-093 只有在所有 M11 blocking decisions 为 FROZEN 时才能 PASS。

---

## 7. Dependency Order

```text
AIC-084
   ↓
AIC-085 ──┐
AIC-086 ──┤
           ↓
AIC-087   AIC-088
     \       /
      \     /
       AIC-089
          ↓
       AIC-090
          ↓
       AIC-091
          ↓
       AIC-092
          ↓
       AIC-093
          ↓
       M10 FROZEN
          ↓
       M11 START
```

AIC-085/086 可以在设计阶段交错推进；AIC-087/088 可以分别设计，但 AIC-089 必须基于两者已稳定 contract。

---

## 8. M11 Hard Gate

以下内容未冻结时禁止进入 M11 broad implementation：

1. Runtime/module ownership 与 durable handoff boundary。
2. Gateway Credential / Provider Credential / secret policy。
3. Request identity / attribution / request id / idempotency / request state machine。
4. Provider/Model/Pricing Version contract。
5. Reservation / Redis atomic protocol、TTL、fencing、recovery。
6. Streaming disconnect / timeout / missing usage 语义。
7. Settlement business key、state machine、transaction boundary、Ledger integration、Closed Period rule。
8. Retry/failover 的 billable-side-effect boundary。
9. Security/privacy/logging/audit/dependency failure contract。
10. Data model、API、migration、failure/test matrix。

M10 可以冻结 M12/M13/M14 的语义，但不得提前实现对应业务能力。

---

## 9. M10 Final Definition of Done

M10 只有同时满足以下条件才算 COMPLETE：

```text
AIC-084 ~ AIC-092 design artifacts complete
cross-document terminology consistent
single-writer durable ownership explicit
request and settlement state machines separated and consistent
no unresolved financial ownership conflict
no unresolved Redis financial-truth conflict
no unresolved duplicate settlement path
no unresolved reservation leak path
no unresolved streaming missing-usage behavior
no unresolved MySQL-after-upstream failure behavior
no unresolved provider secret exposure path
Gateway API machine-readable contract strategy frozen
forward migration strategy frozen
failure/concurrency test matrix frozen
AIC-093 freeze matrix contains no blocking item
Human Review accepts M10
```

M10 final merge 后才允许：

```text
PROJECT_CONTEXT.md: M10 COMPLETE / FROZEN; M11 NEXT
roadmap: M11 active implementation milestone
M11 implementation plan
M11 GitHub Issues
```

不得提前把 M11-M16 全部实现 Issue 一次性铺开。

---

## 10. Local Workflow

新的本地路径统一使用：

```powershell
Set-Location "E:\project\AI-CostOps"
```

开始 M10 本地同步：

```powershell
Set-Location "E:\project\AI-CostOps"
git fetch origin
git switch main
git pull --ff-only origin main
git switch docs/m10-v2-detailed-design
git pull --ff-only origin docs/m10-v2-detailed-design
git status
```

M10 分支必须保持纯文档；若 `git status` 出现 backend/frontend/gateway/runtime dependency 修改，应先移出该分支再继续设计 review。

---

## 11. Explicit Non-Goals

M10 不做：

```text
Gateway runtime implementation
five-provider integration
production routing engine
production Redis Lua code
production settlement worker
new MQ
Kafka
Kubernetes
service mesh
multi-region
prompt observability product
RAG
agent workbench
model quality platform
SAML/SCIM
ERP/GL integration
```

新增基础设施继续遵守：先证明问题，再引入组件。

---

## 12. Program Decision

M10 采用以下治理：

```text
One design branch
→ AIC-084..093 semantic design units
→ one principal design PR
→ Sol architecture review
→ Human design acceptance
→ squash merge
→ M10 FROZEN
→ only then M11 implementation planning
```

这个顺序保持 V1 已验证的 Design → Plan → Issue → PR → Evidence → Acceptance 方法，同时避免两人团队为纯设计阶段制造不必要的分支和基础设施复杂度。
