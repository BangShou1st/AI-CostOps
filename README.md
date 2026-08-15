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

### 日常开发（推荐）— Daily Development Mode

日常开发不需要 Docker 构建前后端镜像：Spring Boot 与 Vite 直接在 Windows 本机运行，Docker 只提供 MySQL / Redis / MinIO 基础设施。

首次使用（或仓库还没有 `.env` 时）：

```powershell
Copy-Item .env.example .env
```

三个终端分别启动：

```powershell
# Terminal 1 — Docker 基础设施
# （会先安全停止 Full Integration 的 backend/frontend 容器，
#   再启动 mysql/redis/minio；不构建任何镜像，不触碰 volume，可重复执行）
.\scripts\dev\start-infra.ps1

# Terminal 2 — Spring Boot 后端（本机 http://localhost:8081）
cd backend
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local

# Terminal 3 — Vite 前端（本机 http://localhost:5173，HMR）
cd frontend
npm ci        # 首次或依赖变更时
npm run dev
```

浏览器打开 `http://localhost:5173`。`local` profile 默认开放公开注册，登录 / refresh cookie / 权限流程与容器模式一致。

停止基础设施（保留全部数据）：

```powershell
.\scripts\dev\stop-infra.ps1
```

### 完整集成模式 — Full Integration Mode

仅用于需要完整容器环境的场合（PR 前 smoke test、Dockerfile / CI / 部署路径验证）：

```powershell
docker compose up -d --build
```

浏览器入口为 `http://localhost:8080`，后端经 Nginx 同源 `/api/v1` 反向代理访问。

### Docker / WSL 资源说明

Daily Development 模式只启动 mysql / redis / minio 三个容器，不产生 backend / frontend 镜像构建，也就不会持续积累 BuildKit cache。本机 Java / Node 进程的内存占用与 Docker 无关；Docker 镜像、layer 与 cache 只占磁盘不占内存。删除镜像 / 清理 cache 后，WSL2 虚拟磁盘文件不会自动收缩，可选维护方法见 `docs/02-development/implementation/05-bootstrap-local-development-runbook.md`。

完整的 IDE / 测试 / PR 流程见 `docs/02-development/implementation/05-bootstrap-local-development-runbook.md`。

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
