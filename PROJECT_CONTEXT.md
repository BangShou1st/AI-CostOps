# AI CostOps — 项目上下文

> 本文件是**当前状态**的权威索引。历史里程碑细节保留在 `docs/` 与 [`CHANGELOG.md`](CHANGELOG.md)，
> 不在此重复。新会话请先读 [`AGENTS.md`](AGENTS.md)。

## 定位

AI CostOps 是一个双人协作开发的企业后端 / FinOps 类项目。

V1 目标：

> 把已经发生的多 Provider AI 成本统一变成可信、可归属、可审批、可对账、可关账的一本内部成本账。

V2 目标：

> 对企业能够控制的 AI API 流量，通过统一 Gateway 在请求发生时完成身份识别、归属、预算预占、实时计量、Provider Routing 和 Settlement，并继续以既有 MySQL Ledger 作为最终财务 truth。

## 当前阶段

```text
FINAL_PRODUCT_VERSION = V3
STABLE_RELEASE = v3.0.0
Feature development = CLOSED
Operational mode = MAINTENANCE_ONLY
V4 = NOT_PLANNED
M20 Production Acceptance = COMPLETE
M21 Operational Readiness = COMPLETE
M22 = CLOSED WITH DEFERRED NON-BLOCKING GATES
M22_BROWSER_ORGANIZATION_ISOLATION = PASS (2026-10-19 supplementary)
Known release-blocking defects = NONE
```

V3 是最终功能版本：功能研发已关闭，项目进入长期维护模式；V4 不在路线图内，不再规划或实现 V4，不新增 Provider 或业务功能，维护任务按真实故障与必要性触发。`STABLE_RELEASE = v3.0.0` 是 `main` 上的当前稳定标签，`dev` 包含尚未合并或发布的维护提交。

M22 原始记录中的两个 `DEFERRED / NON-BLOCKING` gate 需分开解释：independent browser-context organization-isolation 已由 2026-10-19 补充验收完成并判定 PASS（含后续独立复审发现的模型可见性边界整改），见 [2026-10-19 M22 双组织浏览器隔离补充验收](docs/03-acceptance/2026-10-19-m22-browser-organization-isolation.md)；real-provider / OpenCode external credential 仍为 `DEFERRED / NON-BLOCKING`，不随浏览器隔离改为 PASS。历史记录 [m22-v3-production-certification.md](docs/03-acceptance/m22-v3-production-certification.md) 保留其原时间点结论，未改写。

### 冻结入口

```text
docs/02-development/v2-detailed-design/README.md   V2 Detailed Design
docs/03-acceptance/m10-design-freeze-matrix.md      M10 Design Freeze Matrix
docs/02-development/api/gateway-openapi.yaml        Gateway OpenAPI Contract
```

M11+ 实现必须服从 M10 冻结的 ownership、状态机、财务、幂等、失败恢复和 Gateway API 契约；不得把 correctness-critical 决策重新留给实现期猜测。

### 发布基线

```text
v1.0.0 → 982d06a0e9ec844ea687ed746d6b9d8f39d86686
v1.0.1 → b96be614e2d843c101add49fe6daffb9d2343a56
v1.1.0 → 102f287da9bfc922ffaabb1b7244a973a0f813eb
v2.0.0 → 93e359687c2d0e81b096d7a73cc8e04e16a7ecbe
v3.0.0 → 9c55125c1b857e3ccf301875d8886131a9d1d9b0
```

## 已交付能力范围

```text
IAM / Organization          Evidence / Import          Canonical Cost Facts
Duplicate Review            Attribution / Allocation   Expense / Approval
BillingPeriod / Budget / Commitment                  Immutable Ledger / Correction
Reconciliation              Period Close / Reopen      Audit
Gateway（身份/预占/计量/路由/结算）                   Provider Hub / Model Catalog / Pricing
Cost Intelligence（异常/预测/节约建议）                AI Advisor（只解释，不算钱）
Reporting / Workbench        Observability
```

## 数据真相

```text
MySQL
= 身份与财务最终 truth

Redis
= Refresh Session / TTL / Rate Limit / Cache

MinIO / S3
= Evidence Object
```

Budget：

```text
available
= total
- actual
- outstanding commitments
```

已发生的 Provider Cost 不因为预算不足而丢弃。

Ledger：

```text
POSTED Immutable
Correction Append-only
```

## 当前技术基线

```text
Java 21
Spring Boot 4.1.0
Spring MVC
Spring Security
Plain MyBatis
MySQL 8.4
Redis
Flyway
MinIO / S3-compatible storage
React 19
TypeScript 6
Vite 8
Docker Compose
GitHub Actions
JUnit / Testcontainers / ArchUnit
```

Gateway 的 M10 冻结 Runtime 方向为 Java 21 + Spring Boot 4.1.0 + Spring WebFlux / Reactor Netty；M10 不通过文档阶段静默升级依赖版本。

## Runtime 方向

```text
Monorepo
├─ frontend/   React / TypeScript Admin UI
├─ backend/    Java / Spring MVC Control Plane
└─ gateway/    Java / Spring WebFlux + Reactor Netty Data Plane
```

核心原则：

> 一个 Monorepo、两个 Deployable、一个最终财务 Truth。

Control Plane 负责：

```text
IAM / Organization / Budget / Ledger / Reconciliation / Period Close / Audit
Admin / Reporting / Final Settlement financial posting
```

Gateway Data Plane 负责：

```text
OpenAI-compatible API / Internal Gateway Credentials / Request Identity / Rate Limit / Quota
Budget Reservation request-time control / Streaming Proxy / Realtime Metering / Provider Routing
Gateway request / route / usage facts / Gateway Metrics / Audit
```

最终财务 truth：

```text
MySQL Ledger
```

M10 已冻结 Budget Reservation correctness：

```text
MySQL = authoritative reservation correctness
Redis != monetary reservation authority
```

Redis V2 可承担：rate limit、quota window、short idempotency lookup cache、request ephemeral coordination、provider health / circuit state、reservation expiry wake-up hints / non-authoritative cache。

Redis 不承担 Final Ledger、Final Budget、Final Settlement History，也不能独立授权 monetary spend。

## V2 明确不做

```text
SAML / SCIM / Full FOCUS conformance / Automatic FX Engine / Full ERP / GL
Bank payment / Tax automation / Custom Approval DSL / Prompt management product
RAG / Agent workbench / Model quality evaluation platform / Multi-region active-active
Kubernetes migration for its own sake / Cost per PR / issue / agent outcome
```

这些是 V2.1/V3/V4 候选，按真实需求再设计。

## Daily Development Mode

Daily Development Default：只用 Docker 运行基础设施（MySQL / Redis / MinIO），Frontend / Backend / Gateway 三个应用进程全部在本机原生运行：

```text
Frontend      localhost:5173    （Vite，本机，HMR）
Backend       localhost:8080    （Spring Boot，本机）
Gateway       localhost:8081    （Spring WebFlux，本机）
MySQL         localhost:3307    （Docker）
Redis         localhost:6379    （Docker）
MinIO         localhost:9000    （Docker API）
              localhost:9001    （Docker Console）
```

Backend / Frontend / Gateway 的 Docker 镜像可以存在，但它们不是日常本地开发的默认运行时（`Backend / Frontend / Gateway Docker images may exist, but they are NOT the default local development runtime.`）。日常 edit-test 循环禁止反复执行 `docker compose build` / `docker compose up --build` / `docker build backend|frontend|gateway`。

日常统一入口（Node 24+、JDK 21，配置根 `.env` 后启动基础设施）：

```powershell
Set-Location "E:\project\AI-CostOps"
npm run doctor
npm run infra:up
npm run dev
```

Full Compose 仍优先用于 CI / E2E / Security / Smoke / Release 与最终集成 / Docker 验证，而不是日常代码循环。

`dev` 在前台启动本机三端，Ctrl+C 清理本次创建的应用进程树。`test` 包含 Backend/Gateway 单元与架构及前端单元测试；`verify` 执行两个 Maven 模块完整生命周期及前端测试、lint、生产 build。Maven 根任务输出隔离在 `.local-dev/builds/`，不覆盖运行中的模块 JAR。脚本包装、环境配置与状态命令见 `scripts/README.md`。

## Repository Workflow

```text
Issue
→ Short-lived Branch
→ Pull Request
→ CI
→ Human Acceptance
→ Squash Merge
→ main
```

Peer Review 按需，不作为全局 Merge Gate。

## 维护模式

```text
Active development = CLOSED
Feature development = CLOSED
V4 = NOT_PLANNED
New features = OUT_OF_SCOPE
Allowed maintenance = security updates, defect fixes, operational fixes, CI / dependency maintenance, documentation maintenance
Maintenance trigger = real defects and necessity only
```

维护流程：发现真实问题 → 复现 → 最小修复 → 定向回归 → 必要时完整 verify → dev CI / Security → 独立复审。只有在用户明确授权更新稳定版本时，才启动 `main` 的合并或发布流程；不创建常驻维护任务、自动提醒或无期限扫描。
