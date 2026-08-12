# AI CostOps

面向研发团队的多 AI Provider 成本归集、费用核算、预算治理、对账与账期管理平台。

长期目标：

> 无论 AI 消费发生在哪里，最终进入企业统一、可信、可追溯的一本 AI 成本账。

## 当前阶段

```text
V0.2 Architecture Baseline
+
V1 Detailed Design 1.1
+
V1 Implementation Plan 1.0
```

当前已建立：

```text
M0 — Repository Foundation（AIC-002～AIC-008、AIC-010）
```

仓库已具备后端、前端、真实 MySQL 集成测试、容器镜像、完整 Compose 与 CI 基础；M1+ 业务代码尚未开始实现。

## V1 主链路

```text
Provider Statement / Expense Evidence
              ↓
      Evidence & Ingestion
              ↓
      Canonical Cost Facts
              ↓
          Attribution
           ↙      ↘
       Budget    Approval
           ↘      ↙
            Ledger
          ↙       ↘
Reconciliation   Period Close
```

## 技术基线

```text
Backend
Java 21
Spring Boot 4.1
Spring Security
Plain MyBatis（Spring Boot Starter 4.1.0 / Core 3.5.19，不使用 MyBatis-Plus）
Flyway

Data
MySQL 8.4 LTS
Redis
MinIO / S3

Frontend
React 19
TypeScript
Vite
TanStack Query
Ant Design
ECharts

Delivery
Docker
Docker Compose
Nginx
GitHub Actions
```

## 本地启动

复制本地配置并启动完整 M0 环境：

```bash
cp .env.example .env
docker compose up -d --build
docker compose ps
```

Windows PowerShell 使用 `Copy-Item .env.example .env`。默认前端入口为 `http://localhost:8080`；后端通过同源 `/api/v1` 反向代理访问。IDE/HMR 开发方式及测试命令见 `docs/02-development/implementation/05-bootstrap-local-development-runbook.md`。

## 文档结构

```text
docs/
├── 01-blueprint/       # 产品、领域、架构、ADR、Provider 研究
├── 02-development/     # 数据模型、事务、API、权限、实施计划
└── 03-acceptance/      # PR、Milestone、V1 发布验收
```

### 第一次理解项目

从：

```text
docs/01-blueprint/README.md
```

开始。

### 正在开发

从：

```text
docs/02-development/README.md
```

开始。

API 开发统一使用：

```text
docs/02-development/api/openapi.yaml
```

作为 HTTP Path / Method / Request / Response / Schema 的机器可读基线。

### 正在 Review / 测试 / 发布

从：

```text
docs/03-acceptance/README.md
```

开始。

## 核心原则

```text
MySQL = Business / Financial Truth
Redis = Session / TTL / Rate Limit / Cache
MinIO/S3 = Original Evidence
```

Budget：

```text
available
= total
- actual
- outstanding commitments
```

已经发生的 Provider Cost 即使导致超预算，也必须如实进入 Ledger。

Ledger：

```text
POSTED Entry 不直接修改
Correction 通过新增 Reversal / Replacement 完成
```

## Git 协作

```text
Issue
→ Short-lived Branch
→ Pull Request
→ CI
→ Optional Review / Discussion
→ Squash Merge
→ main
```

`main` 不直接开发。

## 真实性边界

本项目设计基于：

```text
Provider 官方文档
真实 Provider 导出 Schema
部分实际账户记录
明确标注的 Synthetic Data
```

不声称：

```text
真实企业生产环境验证
所有 Provider 完整支持
FOCUS Compliance
未实测的性能与规模能力
```
