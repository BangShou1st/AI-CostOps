# AI CostOps — 项目上下文

## 定位

AI CostOps 是一个双人协作开发的企业后端 / FinOps 类项目。

V1 目标：

> 把已经发生的多 Provider AI 成本统一变成可信、可归属、可审批、可对账、可关账的一本内部成本账。

V2 目标：

> 对企业能够控制的 AI API 流量，通过统一 Gateway 在请求发生时完成身份识别、归属、预算预占、实时计量、Provider Routing 和 Settlement，并继续以既有 MySQL Ledger 作为最终财务 truth。

## 当前阶段

```text
V1 = COMPLETE / FROZEN
Current stable (published) release = v1.1.0
M9 = COMPLETE / ACCEPTED (v1.1.0 RELEASED; AIC-074~AIC-083 all PASS)
v1.1.0 = RELEASED
M10 V2 Detailed Design = COMPLETE / FROZEN
AIC-084 ~ AIC-093 = FROZEN / PASS
M10 principal design merge = PR #129 / main@1ed62c68c09458570c5cd04f812a2525028db7a2
V2 Gateway detailed design = FROZEN
M11 Gateway Edge MVP = COMPLETE / ACCEPTED
M12 Identity / Attribution / Budget Reservation = COMPLETE / ACCEPTED
M13 Realtime Metering / Settlement = COMPLETE / ACCEPTED
M14 Multi-provider Routing / Resilience = COMPLETE / ACCEPTED
M14 merge baseline = PR #146 / main@a9afc8aef64b9d66608ccc19c611b703e545610b
(feat(m14): deliver multi-provider routing and resilience)
M15 Hybrid Reconciliation = IMPLEMENTATION IN DELIVERY (feat/m15-hybrid-reconciliation; pending independent review + user merge)
```

M10 冻结入口：

```text
docs/02-development/v2-detailed-design/README.md
docs/03-acceptance/m10-design-freeze-matrix.md
docs/02-development/api/gateway-openapi.yaml
```

M11+ 实现必须服从 M10 冻结的 ownership、状态机、财务、幂等、失败恢复和 Gateway API 契约；不得把 correctness-critical 决策重新留给实现期猜测。

V1 状态：

```text
M0–M8 全部完成
AIC-001 ~ AIC-073 全部完成
AIC-073 Final Human Acceptance / Release Sign-off = ACCEPTED
Release blocker = NONE
v1.0.0 = RELEASED
v1.0.1 = RELEASED
```

发布基线：

```text
v1.0.0 → 982d06a0e9ec844ea687ed746d6b9d8f39d86686
v1.0.1 → b96be614e2d843c101add49fe6daffb9d2343a56
v1.1.0 → 102f287da9bfc922ffaabb1b7244a973a0f813eb
```

`v1.0.1` 是 V1 发布后的补丁版本，通过 PR #103 加固 Import lease recovery 在 MySQL deadlock race 下的 bounded retry；不改变 API、Schema 或 V1 产品范围。

## V1 已完成范围

```text
IAM / Organization
Evidence
Import
Canonical Cost Facts
Duplicate Review
Attribution / Allocation
Expense / Approval
BillingPeriod / Budget / Commitment
Immutable Ledger / Correction
Reconciliation
Period Close / Reopen
Audit
Workbench / Reporting
```

## V1 数据真相

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

## V1 验证状态

```text
Backend Unit Tests        437 PASS
Backend Integration       800 PASS
Backend Architecture       34 PASS
Frontend Vitest           432 PASS
Frontend Lint             PASS
Compose Smoke             PASS
Browser UAT              32/32 PASS
State Branches           14/14 PASS
P0/P1 Defects                0
```

AIC-073 最终签署记录：

```text
docs/03-acceptance/aic-073-final-human-acceptance.md
```

V1 冻结历史计划与 M0–M8 证据继续保留，不作为当前 backlog 解释。

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

## V1.1 / M9 — Production Foundation

M9 不新增 Realtime Gateway 业务功能。

目标：

> 把已完成的 V1 财务闭环提升为能够可靠承载后续 Gateway 的生产工程基础。

M9 范围：

```text
Audit closure
Production configuration hardening
Metrics / Prometheus
Grafana / alerting
Browser E2E automation
Security CI
Backup / restore drills
Provider certification process
10k / 100k / 500k import benchmark
Read-model performance evidence
Operational / incident runbooks
v1.1 release evidence
```

Transactional Outbox / RabbitMQ 不作为默认 M9 DoD。先 measure → SQL/index/batch/concurrency tuning；只有真实证据证明 DB-backed worker 不足时再评估。

已发布：

```text
v1.1.0
```

## V2 Runtime Direction

采用：

```text
Monorepo
├─ frontend/   React / TypeScript Admin UI
├─ backend/    Java / Spring MVC Control Plane
└─ gateway/    Java / Spring WebFlux + Reactor Netty Data Plane
```

核心原则：

> 一个 Monorepo、两个 Deployable、一个最终财务 Truth。

Control Plane 继续负责：

```text
IAM
Organization
Budget
Ledger
Reconciliation
Period Close
Audit
Admin / Reporting
Final Settlement financial posting
```

Gateway Data Plane 负责/将负责：

```text
OpenAI-compatible API
Internal Gateway Credentials
Request Identity / Attribution
Rate Limit / Quota
Budget Reservation request-time control
Streaming Proxy
Realtime Metering
Provider Routing
Gateway request / route / usage facts
Gateway Metrics / Audit
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

Redis V2 可承担：

```text
rate limit
quota window
short idempotency lookup cache
request ephemeral coordination
provider health / circuit state
reservation expiry wake-up hints / non-authoritative cache
```

Redis 不承担 Final Ledger、Final Budget、Final Settlement History，也不能独立授权 monetary spend。

## V2 Roadmap

```text
M9  — V1.1 Production Foundation                COMPLETE / ACCEPTED
M10 — V2 Detailed Design                        COMPLETE / FROZEN
M11 — Gateway Edge MVP                          COMPLETE / ACCEPTED
M12 — Identity / Attribution / Budget Reservation  COMPLETE / ACCEPTED
M13 — Realtime Metering / Settlement            COMPLETE / ACCEPTED
M14 — Multi-provider Routing / Resilience       COMPLETE / ACCEPTED
M15 — Hybrid Reconciliation                     IN DELIVERY
M16 — V2 Production Acceptance                  FUTURE
```

详细路线：

```text
docs/01-blueprint/product/11-roadmap.md
```

V1 → V2 总体设计基线：

```text
docs/superpowers/specs/2026-08-27-v1-to-v2-production-gateway-design.md
```

M10 最终详细设计：

```text
docs/02-development/v2-detailed-design/
docs/03-acceptance/m10-design-freeze-matrix.md
```

## V2 Core 明确不做

```text
SAML / SCIM
Full FOCUS conformance
Automatic FX Engine
Full ERP / GL
Bank payment
Tax automation
Custom Approval DSL
Prompt management product
RAG
Agent workbench
Model quality evaluation platform
Multi-region active-active
Kubernetes migration for its own sake
Cost per PR / issue / agent outcome
```

这些进入 V2.1/V3/V4 候选，按真实需求再设计。

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

启动基础设施：

```powershell
Set-Location "E:\project\AI-CostOps"
.\scripts\dev\start-infra.ps1
```

Full Compose 仍优先用于 CI / E2E / Security / Smoke / Release 与最终集成 / Docker 验证，而不是日常代码循环；详细警告见 README.md 的"本地完整 Compose Quick Start"段落。

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

## 当前下一步

```text
1. Plan and execute M15 Hybrid Reconciliation from the frozen V2 architecture
2. Preserve the existing financial truth / settlement / routing invariants from M10-M14
3. Keep Daily Development native-app + Docker-infra only
4. Continue Sol independent final review before milestone closure / merge
```
