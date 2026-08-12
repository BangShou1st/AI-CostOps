# AI CostOps — 项目上下文

## 定位

AI CostOps 是一个双人协作开发的企业后端 / FinOps 类项目。

V1 目标：

> 把已经发生的多 Provider AI 成本统一变成可信、可归属、可审批、可对账、可关账的一本内部成本账。

## V1 范围

```text
IAM / Organization
Evidence
Import
Canonical Cost Facts
Attribution
Expense / Approval
BillingPeriod / Budget
Immutable Ledger
Reconciliation
Period Close
Workbench
```

## V1 不做

```text
Realtime AI Gateway
Microservices
Kafka
Kubernetes
Automatic FX
SAML / SCIM
Full ERP GL
Custom Approval DSL
```

## 数据真相

```text
MySQL
= 身份与财务最终真相

Redis
= Refresh Session / TTL / Rate Limit / Cache

MinIO / S3
= Evidence Object
```

## Budget

```text
available
= total
- actual
- outstanding commitments
```

已发生的 Provider Cost 不因为预算不足而丢弃。

## Ledger

```text
POSTED Immutable
Correction Append-only
```

## Repository

```text
一个 Public GitHub Repository
两个真实 Contributor
Protected main
Short-lived Branch
PR + CI + Peer Review
Squash Merge
```

## 文档

```text
docs/01-blueprint
docs/02-development
docs/03-acceptance
```

API 的机器可读开发契约：

```text
docs/02-development/api/openapi.yaml
```

## 实施计划

稳定计划 ID：

```text
AIC-001 ... AIC-073
```

当前阶段：

```text
M0 Repository Foundation
AIC-001 Repository Governance
```
