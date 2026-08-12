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
