# 01. 模块边界设计

## 1. Monorepo 结构

```text
AI-CostOps/
├── backend/
├── frontend/
├── docs/
├── deploy/
├── .github/
├── scripts/
├── compose.yaml
├── compose.dev.yaml
└── .env.example
```

后端根包：

```text
com.aicostops
```

业务模块优先采用：

```text
<module>/
├── api/
├── application/
├── domain/
└── infrastructure/
```

只有真实需要时才创建对应层，不为了目录整齐制造空包。

禁止整个项目变成：

```text
controller/
service/
mapper/
entity/
```

这种按技术层全局切分的结构。

## 2. V1 模块

### `iam`

负责：

```text
User
Credential
Role
Permission
RoleAssignment
Invitation
Authentication
Authorization Context
```

不负责 Project、Ledger、Budget。

### `organization`

负责：

```text
Organization
OrganizationMember
Team
TeamMember
CostCenter
Project
ProjectMember
ProviderAccount
```

对外暴露只读 Port，例如：

```text
ProjectDirectory
MembershipDirectory
ProviderAccountDirectory
```

### `evidence`

负责：

```text
Evidence Metadata
Checksum / Dedup
ObjectStoragePort
Evidence Download Authorization Hook
```

不解析 Provider 行。

### `ingestion`

负责：

```text
ImportBatch
ImportAttempt
RawProviderRecord
ImportIssue
ProviderAdapter Registry
Schema Inspection
Parse Orchestration
```

可以调用 `cost` 的写入 Port，但**不能直接 Posting Ledger**。

### `cost`

负责：

```text
ExternalDocument
ConsumptionFact
PricingFact
ChargeFact
AttributionHint
```

保存 Provider 事实，不决定企业内部归属。

### `attribution`

负责：

```text
AllocationRule
AllocationDecision
AllocationLine
Rule Evaluation
Manual Allocation
```

依赖 `cost` / `organization`。

### `expense`

负责：

```text
ExpenseClaim
ApprovalCase
ApprovalAction
```

依赖 Evidence、Organization、Attribution、Budget Port。

### `budget`

负责：

```text
Budget
BudgetCommitment
BudgetCommitmentUsage
Commit / Release / Consume
Available Amount
```

最终正确性只依赖 MySQL，不依赖 Redis Lock。

### `ledger`

负责：

```text
LedgerPosting
LedgerEntry
CorrectionGroup
Posting Orchestration
Immutable History
```

依赖：

```text
Attribution
Budget
PeriodGuard
Audit
```

### `reconciliation`

负责：

```text
ReconciliationRun
ReconciliationCase
Matching
Difference Classification
Resolution
```

只通过 Cost/Ledger Read Model 对账。

### `period`

负责：

```text
BillingPeriod
PeriodCloseRun
PeriodCloseCheck
PeriodGuard
Close Coordinator
Reopen
```

Close Blocker 通过：

```text
CloseBlockerProvider
```

SPI 聚合，避免 `period` 直接依赖所有业务模块。

### `audit`

负责 Append-only `AuditEvent`。

这是低层模块，不反向依赖业务模块。

### `reporting`

只读：

```text
Workbench
Cost Summary
Budget Summary
Provider Distribution
```

可以使用 MySQL Read Query + Redis Cache，不允许修改 Ledger/Budget。

### `shared`

只允许放稳定 Primitive：

```text
Money
CurrencyCode
Clock
PageRequest
ProblemCode
DomainException Base
```

禁止演化成万能 Common Service。

## 3. 依赖方向

```text
shared
↑
各业务模块

cost ← attribution
evidence ← ingestion
organization ← ingestion / attribution / budget
period ← budget / ledger
budget ← ledger
ledger ← reconciliation / reporting
```

核心要求：

```text
Provider Adapter
不能依赖 Ledger

Cost
不能依赖 Attribution

Reporting
不能拿到 Financial Mutation Repository

Shared
不能依赖业务模块
```

用 ArchUnit 固化。

### 3.1 cost.review（M3 Group 2 起）

Duplicate Review 工作流放在 `cost.review` 子包，不进 `cost.application` /
`cost.infrastructure`（它们的 allowed 列表冻结且不含 iam/audit）：

```text
cost.review.domain          → cost.domain / shared / java
cost.review.application     → cost.review.domain / cost.domain / iam（授权上下文）/ shared / Spring / Jackson
cost.review.infrastructure  → cost.review.application / cost.review.domain / cost.domain / audit.application / MyBatis / Spring
cost.review.api             → cost.review.application / cost.review.domain / shared / Spring Web
```

白名单按 `cost.review.<layer>..` 精确列出，绝不放宽成整个 `cost.review..`
（否则 application → infrastructure 会被放过）。全 `cost..` 不得依赖
`ingestion / attribution / ledger / budget / reporting`；Scan 只读
charge_fact 的 confirmed-attempt lineage（IDs join），不 import ingestion 代码。

### 3.2 attribution foundation（M3 Group 2 起）

`attribution` 模块当前只有 persistence foundation：

```text
attribution.domain          → shared / java
attribution.application     → attribution.domain / shared / java
attribution.infrastructure  → attribution.application / attribution.domain / shared / Spring / MyBatis
```

不依赖 iam / audit / ingestion / evidence / cost——DB lineage 与 FK 用 IDs 即可。
Group 3 若需要 cost read model，再按真实调用点最小放宽；本组不提前放宽。
attribution 反向禁止：cost 不能依赖 attribution（同上）。

### 3.3 allocation workflow（M3 Group 3，#49/#50 起）

事务命令服务不能放进 `attribution`：`attribution.application` 的 ArchUnit
白名单只有 domain/shared/java（无 Spring transaction / iam / audit），
`attribution..` 整体不得依赖 iam/audit，`cost..` 又不得依赖 attribution。
因此 orchestration 放在独立模块 `com.aicostops.allocation`（无既有依赖
限制）：

```text
allocation.api            → allocation.application / shared / Spring Web
allocation.application    → attribution.application（repository ports）/
                            attribution.domain / cost.domain（read models）/
                            iam（授权上下文）/ shared / Spring（事务、幂等）/
                            audit port（自建接口）
allocation.infrastructure → allocation.application / attribution.infrastructure /
                            audit.application（AuditService adapter）/
                            MyBatis / Spring
```

职责：manual draft / replace-lines / confirm（#49）、rule version / archive /
deterministic evaluator / proposal（#50）、decision/rule 查询。attribution 的
repository 接口与 mapper 只增加查询与状态 transition（无 schema 变更），
`attribution.application` 保持纯 Java。cost read API 仍在 `cost` 模块
（`cost.api` 做 COST_READ 授权，`cost.application` 只接收 orgId）。

## 4. 事务放在哪里

`@Transactional` 放在 Application Use Case。

例如：

```text
ledger.application.PostChargeUseCase
```

负责协调：

```text
PeriodGuard
AllocationQuery
LedgerRepository
BudgetPort
AuditPort
```

Controller 只做：

```text
HTTP Mapping
Validation
Authorization
Command Mapping
Response Mapping
```

大文件解析期间不持有长数据库事务。

## 5. MyBatis 边界

Mapper / XML 属于：

```text
infrastructure.persistence
```

Domain/Application 不直接依赖 MyBatis API。

以下 SQL 必须清晰可审查：

```text
Budget Conditional UPDATE
Import Job Claim
Ledger Posting
Period Close Lock
Reconciliation Aggregate
```

## 6. 双人 Ownership

### Dev A 主责

```text
Architecture
Budget
Ledger
Reconciliation
Period
DB Concurrency
Docker / CI
```

### Dev B 主责

```text
IAM
Organization
Evidence
Ingestion
Provider Adapter
Expense
React Workflow
```

Ownership 不等于禁止对方修改。V1 结束前，两个人都要至少完成一次非主责模块的真实实现或修复。

## M5 Immutable Ledger Boundary

`ledger.application` owns provider/expense posting, read models, and correction
use cases. It may call the narrow `LedgerBudgetPort`, allocation/source read
ports, authorization, and audit ports; it must not import budget or ingestion
infrastructure classes directly.

`budget.application` remains the owner of `BillingPeriod`, `Budget`, and
`Commitment` locking and counter mutation. A posting never writes those tables
through a mapper of its own. `ledger.infrastructure` exposes append-only
ledger INSERT/SELECT seams; no UPDATE or DELETE path exists for ledger rows.
