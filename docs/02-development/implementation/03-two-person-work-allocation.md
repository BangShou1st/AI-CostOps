# 03. 双人任务与 Ownership 分配

## 目标

希望最后呈现的是：

```text
清晰 Ownership
+
真实并行开发
+
互相 Code Review
+
两个人都能解释整个系统
```

不是强求代码行数 50/50。

## Dev A 主责

```text
Architecture
Backend Foundation
MySQL / Flyway / Testcontainers
Scope Authorization
DeepSeek / OpenAI Adapter
Canonical Cost Persistence
Attribution Core
BillingPeriod
Budget
Ledger
Correction
Reconciliation
Reporting SQL
Docker / CI
Concurrency / Performance
Release
```

核心价值是事务、幂等、并发和财务正确性。

## Dev B 主责

```text
Repository Governance
Frontend Foundation
IAM / Auth
Evidence
Import Worker
ProviderAdapter Framework
MiMo / Kimi / GLM
Duplicate Review
Allocation Rules
Expense / Approval
Ledger Query / Lineage
Close Blockers
React Workflows
Runtime Failure / Security
Release Docs
```

Dev B 不只是“前端负责人”，会拥有明显后端模块。

## 交叉 Ownership

Dev A 必须真实参与财务域以外，例如：

```text
Organization
DeepSeek Adapter
OpenAI Adapter
```

Dev B 必须真实参与 Ingestion/Frontend 以外，例如：

```text
Ledger Query
Ledger Invariant Tests
Close Blockers
Audit Review
```

## Review 规则

```text
Dev A 写 → Dev B Review
Dev B 写 → Dev A Review
```

作者不能作为自己的 Required Reviewer。

## 必须共同确认的设计点

即使只有一个人实现，下列变化也需要双方明确同意：

```text
Money 语义
Budget 公式
Ledger 不可变
Provider Canonical Mapping
Period Close Blockers
Permission Model
核心表关系
```

## Knowledge Transfer

每个 Milestone 结束时，Owner 至少要向 Reviewer 讲清楚：

```text
业务不变量
数据模型
事务边界
失败路径
关键测试
```

Reviewer 能复述“为什么安全”即可，不要求形式化会议。

## 推荐 Pairing 的任务

```text
第一次 Budget 并发测试
第一次 Ledger Posting
第一次 ProviderAdapter Contract
Period Close / Posting Race
Refresh Token Race
```

可以结对讨论，但最终 PR 仍有明确 Owner + Reviewer。

## 简历一致性

两个人都可以放同一个 GitHub URL。

各自写真实 Ownership：

> 双人协作项目，主导/负责 X、Y、Z；通过 GitHub Issue、PR、Code Review、CI 进行协作。

不要两个人都声称独立完成全部模块。
