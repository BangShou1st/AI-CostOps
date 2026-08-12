# 12. 实现就绪检查

这份文档只回答一个问题：

> 现在的设计是否已经足够明确，可以停止设计并开始开发？

## 1. 已冻结技术选择

```text
Java 21
Spring Boot 4.1
Spring MVC
Spring Security
Plain MyBatis 4
Flyway

MySQL 8.4 LTS
Redis
MinIO/S3

React 19
TypeScript
Ant Design
TanStack Query

Docker Compose
GitHub Actions
```

## 2. 已冻结业务正确性

```text
Evidence != Ledger
Raw Data 保留
Parser Versioned
Retry Safe
Consumption != Charge
未知 Price 不推断
Provider Identity = Attribution Hint
No Allocation => No Posting
Allocation Sum Exact
Available = Total - Actual - Outstanding Commitment
已发生成本即使超预算也 Posting
POSTED Ledger Immutable
Correction Append-only
Reconciliation Difference Explicit
CLOSED 拒绝普通写
Redis 不做 Financial Truth
```

## 3. 进入开发前必须能回答

```text
每张表属于哪个模块？
核心事务在哪里？
重复 HTTP Request 怎么办？
Import Worker Crash 怎么恢复？
Budget 并发怎么防超分配？
Redis 丢失为什么不会破坏 Ledger？
LedgerEntry 如何追到 Evidence？
Close Blocker 有哪些？
OpenAI Evidence 边界是什么？
两个人 Git Workflow 是什么？
```

## 4. Dependency Path

```text
Repository / Build / CI
→ IAM / Organization
→ Evidence
→ Import
→ Canonical Facts
→ Attribution
→ Expense + Period/Budget
→ Ledger
→ Reconciliation + Close
→ Workbench
→ Hardening
```

详细拆分见：

```text
docs/implementation/
```

## 5. 可在实现时再定的细节

这些不需要重新 Design Review：

```text
具体 Maven Plugin Patch Version
MySQL Connector Patch Version
Redis Client Configuration
POI / CSV Library Patch Version
具体 Class Name
Batch Size（Benchmark 后）
```

## 6. 必须重新 ADR 的变化

```text
MyBatis → JPA
Redis → Financial Truth
Kafka/RabbitMQ 强行进入 V1
Microservice Split
Mutable Ledger
Multi-org SaaS
Automatic FX
Gateway 移入 V1
```

## 7. Repository Readiness

开发前确认：

```text
Backend Package-by-feature
Frontend Feature-oriented
Root Monorepo Layout
.gitignore
.env.example vs .env
Maven Wrapper tracked
package-lock tracked
Flyway tracked
Provider Raw Evidence not tracked
Docker Volume / Build Output not tracked
```

## 8. Implementation Plan

已经存在：

```text
docs/implementation/
```

定义：

```text
M0-M8
AIC-001..AIC-073
Owner / Suggested Reviewer
Dependency
Branch
Tests
CI
Release Gate
Risk Checkpoint
```

因此一旦一次性审查通过，**计划层面不存在继续阻塞开发的理由**。
