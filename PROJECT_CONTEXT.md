# AI CostOps — 项目上下文

## 定位

AI CostOps 是一个双人协作开发的企业后端 / FinOps 类项目。

V1 目标：

> 把已经发生的多 Provider AI 成本统一变成可信、可归属、可审批、可对账、可关账的一本内部成本账。

## 当前阶段

```text
M8 Stage 2 / V1 Release Candidate
```

状态：

```text
M0–M8 全部完成
AIC-001 ~ AIC-073 全部完成
Release Candidate 已完成核心验证
等待 v1.0.0 Release 收尾
```

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

## 验证状态

```text
Backend Unit Tests        437 PASS
Backend Integration       795 PASS
Backend Architecture       34 PASS
Frontend Vitest           420 PASS
Frontend Lint             PASS
Compose Smoke             PASS
Browser UAT              32/32 PASS
State Branches           14/14 PASS
P0/P1 Defects                0
```

## 已完成模块

```text
Backend
Frontend
Import
Cost
Allocation
Budget
Expense
Ledger
Reconciliation
Period Close
RBAC
Audit
```

## Repository

```text
一个 Public GitHub Repository
两个真实 Contributor
Protected main
Short-lived Branch
PR + CI + Optional Peer Review
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

当前仓库可执行基线：Java 21 / Spring Boot 4.1.0 / MyBatis Spring Boot Starter 4.1.0（Core 3.5.19）、React 19 / TypeScript 6 / Vite 8、MySQL 8.4 / Redis / MinIO、Docker Compose 与 GitHub Actions。

## Daily Development Mode

日常开发只运行基础设施（MySQL / Redis / MinIO），Backend 与 Frontend 直接在本机运行：

```text
Frontend      localhost:5173    （Vite，本机，HMR）
Backend       localhost:8080    （Spring Boot，本机）
MySQL         localhost:3307    （Docker）
Redis         localhost:6379    （Docker）
MinIO         localhost:9000    （Docker API）
              localhost:9001    （Docker Console）
```

启动：`.\scripts\dev\start-infra.ps1`

## 剩余事项

```text
Release 文档收尾
v1.0.0 tag 创建
```
