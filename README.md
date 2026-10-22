# AI CostOps

<p align="center">
  <b>把多 AI Provider 的成本，变成一本可信、可归属、可审批、可对账、可关账的内部成本账。</b>
</p>

<p align="center">
  <a href="#-快速开始">快速开始</a> •
  <a href="#-架构">架构</a> •
  <a href="#-功能">功能</a> •
  <a href="#-开发">开发</a> •
  <a href="#-文档">文档</a> •
  <a href="#-贡献">贡献</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/version-v3.0.0-blue?style=flat-square" alt="version" />
  <img src="https://img.shields.io/badge/status-MAINTENANCE_ONLY-orange?style=flat-square" alt="status" />
  <img src="https://img.shields.io/badge/java-21-orange?style=flat-square" alt="java" />
  <img src="https://img.shields.io/badge/frontend-React_19-61DAFB?style=flat-square" alt="react" />
  <img src="https://img.shields.io/badge/license-proprietary-blue?style=flat-square" alt="license" />
</p>

---

## 📖 这是什么

AI CostOps 是一个面向研发团队的 **AI 成本治理（FinOps for AI）** 平台。

企业里同时存在十几种 AI Provider —— OpenAI、DeepSeek、MiMo、Kilo Gateway、OpenCode Zen、自建 OpenAI-Compatible 网关。它们的账单格式不同、计费口径不同、导出周期不同。AI CostOps 把这些**已经发生**的消费统一收敛成一套内部财务事实：

```text
多 Provider 账单 / 导出文件  ─┐
                              ├─→  标准成本事实  ─→  归属分摊  ─→  预算与审批  ─→  不可变账本  ─→  对账与关账
Gateway 实时请求与计量      ─┘                                             └─→  异常检测与节约建议
```

V3 起，平台还内置统一 **AI Gateway 数据面**：对可控的 API 流量在请求发生时完成身份识别、预算预占、实时计量与多 Provider 路由，结算结果回写同一本账。

### 核心承诺

| 承诺 | 含义 |
|---|---|
| **一个最终真相** | MySQL Ledger 是唯一财务真相，Redis / 缓存 / 报表都不是 |
| **缺失用量 ≠ 零成本** | 拿不到用量数据时绝不静默按 0 计费，而是显式标记未知并阻断结算 |
| **已 POSTED 的账本不可变** | 更正只能追加 Correction 流水，永不原地改写历史 |
| **账期关闭可回滚** | 关账后写保护；重开需要显式动作与审计记录 |
| **权限按组织隔离** | 跨租户的私有模型、元数据、价格版本一律不可见 |

---

## 🚀 快速开始

### 前置要求

| 依赖 | 版本 | 说明 |
|---|---|---|
| Node.js | ≥ 24.14.0 | 根任务编排器与前端 |
| JDK | ≥ 21 | 后端与 Gateway |
| Docker Engine | 任意近期版本 | 本地跑 MySQL / Redis / MinIO |

> 首次使用或依赖变更后需要 `npm run setup`（在 `frontend/` 执行 `npm ci`）。根 `package.json` **零依赖**，无需在根目录 `npm install`。

### 三条命令跑起来

```bash
# 1. 配置本地环境变量
cp .env.example .env        # Windows PowerShell: Copy-Item .env.example .env

# 2. 启动基础设施（MySQL / Redis / MinIO，容器化）
npm run infra:up

# 3. 启动三个应用进程（Backend / Gateway / Frontend，本机原生）
npm run dev
```

启动后访问：

| 服务 | 地址 |
|---|---|
| 前端（Vite HMR） | http://localhost:5173 |
| 后端（Spring MVC） | http://localhost:8080 |
| Gateway（Spring WebFlux） | http://localhost:8081 |
| MySQL | `localhost:3307` |
| Redis | `localhost:6379` |
| MinIO API / Console | `localhost:9000` / `localhost:9001` |

开发环境默认账号（**仅限本地**）：`admin@example.test` / `change-me-local-only`

按 `Ctrl+C` 停止 `npm run dev` 启动的进程树；基础设施用 `npm run infra:down` 单独停止（保留数据）。

> **日常开发只跑基础设施容器。** 三个应用的 Docker 镜像只用于 CI、Security 扫描与最终集成验证，**不是**默认本地运行时。

---

## 🏗️ 架构

### Monorepo 布局

```text
AI-CostOps/
├── backend/      Java 21 · Spring Boot 4.1 · Spring MVC ·  控制面（637 个源文件）
│   └── src/main/java/com/aicostops/
│       ├── iam/           身份、组织、角色、作用域授权
│       ├── ingestion/     Provider 账单导入、去重、规范化
│       ├── evidence/      费用证据与对象存储
│       ├── cost/          标准成本事实与重复指纹
│       ├── attribution/   归属规则与分摊决策
│       ├── expense/       报销单与审批
│       ├── budget/        账期、预算、承诺、预占
│       ├── ledger/        不可变账本与更正
│       ├── reconciliation/ 对账与关账
│       ├── gatewayadmin/  Gateway 管理面
│       ├── gatewaysettlement/ Gateway 结算落账
│       ├── providerhub/   Provider 连接、模型目录、探测
│       ├── intelligence/  异常检测、预测、节约建议
│       ├── advisor/       AI 顾问（只解释，不算钱）
│       ├── routing/       路由策略
│       ├── reporting/     报表与看板
│       ├── observability/ 指标与追踪
│       └── shared/        跨模块基础件（金额、错误、Web、安全）
│
├── gateway/      Java 21 · Spring Boot 4.1 · Spring WebFlux ·  数据面（97 个源文件）
│   └── src/main/java/com/aicostops/gateway/
│       ├── auth/       内部网关凭证鉴权
│       ├── budget/     请求时预算预占
│       ├── metering/   实时用量计量
│       ├── quota/      配额与速率限制
│       ├── ratelimit/  限流
│       ├── routing/    多 Provider 路由与熔断
│       ├── resilience/ 失败与降级
│       ├── provider/   opencode / kilo / mimo / openai / custom 适配器
│       └── persistence/ 请求、路由、用量事实落库
│
├── frontend/     React 19 · TypeScript · Vite · Ant Design · TanStack Query · ECharts
│
├── scripts/      Node ESM 任务编排器（零依赖、跨平台）
├── deploy/       生产与可观测性编排
└── docs/         蓝图 / 开发 / 验收 / 运维 四层文档
```

### 三条核心规则

```text
one monorepo / two deployables / one final financial truth
```

1. **MySQL 是权威** —— 身份、预算预占正确性、最终结算、账本，全在 MySQL。
2. **Redis 不是财务真相** —— 只做刷新会话、TTL、限流、缓存与协调；**不能独立授权任何金额支出**。
3. **Gateway 是事实写入方，CostOps Core 是财务写入方** —— Gateway 写请求/路由/用量事实；结算、账本、预算实际值、承诺由 Core 落账。

### 数据流

```text
① 后发账单流（Post-billing）
   Provider 导出 → Import 批次 → 规范化 → 原始留存 → 标准成本事实 → 归属 → 预算实际值 → 账本

② 实时流（Realtime）
   客户端 → Gateway 鉴权 → 预算预占 → 路由 → 流式代理 → 用量计量
          → 请求/路由/用量事实 → 结算 Worker → 账本

③ 治理流（Governance）
   成本事实 → 异常检测 → 预测 → 反事实节约建议 → AI 顾问解释 → 人工审批
```

---

## ✨ 功能

### 成本归集与核算

- **多 Provider 导入**：DeepSeek、MiMo、Kimi、GLM、OpenAI 及自定义 OpenAI-Compatible 端点的账单解析、幂等导入、原始证据留存
- **标准成本事实**：消费 / 定价 / 费用三层分离（`consumption_fact` / `pricing_fact` / `charge_fact`），避免金额语义混淆
- **归属与分摊**：规则引擎驱动的项目 / 团队 / 成本中心归属，支持多因子的确定性分摊
- **不可变账本**：POSTED 不可变，Correction 追加式更正，全链路可追溯
- **对账与关账**：混合对账（账单 ↔ 网关）、账期关闭与重开、关闭期写保护

### 实时网关（V2+）

- **请求时预算预占**：MySQL 事务内预占，释放 / 结算有界
- **实时计量**：流式与非流式用量采集，SSE 分片合法性与完成标记校验
- **多 Provider 路由**：健康检查、熔断、降级与跨 Provider 故障隔离
- **内部网关凭证**：可撤销、可轮换、带 HMAC 签名

### 成本智能（V3）

- **异常检测**：基于 median / MAD 的稳健 z-score，确定性算法，不依赖 LLM
- **预测**：DAMPED_HOLT 阻尼 Holt，要求可观测历史窗口，拒绝反转主导窗口
- **节约建议**：反事实建模，基于真实成本事实给出可执行建议
- **AI 顾问**：只解释金融事实，**不计算金额** —— 金额永远来自账本

### 供应商治理

- **Provider Hub**：版本化连接档案、加密凭证、模型目录、定价版本与路由治理
- **免费层准入**：仅准入有证据的免费 Chat 模型，准入策略版本化可追溯
- **SSRF 防护**：自定义端点做 DNS 解析与公网地址校验，阻断内网回环

### 组织与权限

- 组织 / 团队 / 项目 / 成本中心多级作用域，角色按作用域授权
- 双组织浏览器隔离：跨租户私有模型元数据不可见
- 审计事件不可变追加，敏感 key 名拒绝入库

---

## 🛠️ 开发

### 统一命令入口

在仓库根目录使用（Node 24+ / JDK 21）：

| 命令 | 作用 |
|---|---|
| `npm run setup` | 在 `frontend/` 执行 `npm ci`（首次或依赖变更后） |
| `npm run doctor` | 检查 Node / Java / npm / Docker / Maven wrapper / 前端依赖 / 端口 |
| `npm run status` | 检查三个应用的端口与 HTTP 健康 |
| `npm run infra:up` / `infra:status` / `infra:down` | 管理 MySQL / Redis / MinIO |
| `npm run dev` | 前台启动 Backend + Gateway + Frontend（local profile） |
| `npm run build` | 两个 Maven 模块 package + 前端生产构建 |
| `npm run test` | 脚本测试 + 单元 / 架构测试 + 前端单元测试 |
| `npm run verify` | 完整验证：脚本生命周期 + 两个模块完整 verify + 前端 test / lint / build |

所有任务支持 `--dry-run` 预览命令计划：

```bash
npm run dev -- --dry-run
```

详见 [scripts/README.md](scripts/README.md)。

### 单独运行模块

```bash
# Backend
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local

# Gateway
cd gateway && SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run

# Frontend
cd frontend && npm run dev
```

### 测试分层

```text
单元测试      mvnw -B -DexcludedGroups=architecture,integration test
架构测试      mvnw -B -Dgroups=architecture test
集成测试      mvnw -B verify          # Testcontainers: MySQL 8.4 + Redis
前端单测      cd frontend && npm test -- --run
前端 Lint     cd frontend && npm run lint
浏览器 E2E    cd frontend && npm run test:e2e
脚本测试      npm run test:scripts
```

集成测试使用 **Testcontainers** 启动真实 MySQL 8.4 与 Redis，不使用 H2 或内存替身 —— 财务正确性必须在真实数据库语义下验证。

### 环境变量

完整清单见 [.env.example](.env.example)。关键项：

| 变量 | 用途 |
|---|---|
| `AICOSTOPS_JWT_SIGNING_KEY` | JWT 签名密钥（生产必填，缺失则启动失败） |
| `SPRING_DATASOURCE_*` / `SPRING_DATASOURCE_URL` | 数据库连接 |
| `AICOSTOPS_ALLOWED_ORIGINS` | 允许的浏览器 Origin（生产禁止 localhost） |
| `AICOSTOPS_DEV_BOOTSTRAP_ENABLED` | 开发引导管理员开关（生产必须为 `false`） |
| `AICOSTOPS_ALLOW_PUBLIC_REGISTRATION` | 公开注册开关 |
| `MINIO_*` / `AICOSTOPS_STORAGE_*` | 对象存储 |

生产环境下，配置校验器会**拒绝**默认回退密钥、localhost Origin 与开发引导开关。

---

## 📚 文档

| 目录 | 内容 |
|---|---|
| [docs/01-blueprint/](docs/01-blueprint/) | 产品范围、领域模型、业务规则、架构、ADR、路线图 |
| [docs/02-development/](docs/02-development/) | 详细设计、实现记录、API 契约（OpenAPI）、运维手册 |
| [docs/03-acceptance/](docs/03-acceptance/) | 里程碑验收证据矩阵与逐次验收记录 |
| [docs/04-operations/](docs/04-operations/) | V3 生产运维 Runbook |
| [PROJECT_CONTEXT.md](PROJECT_CONTEXT.md) | 项目状态、里程碑、冻结基线的权威索引 |

### 关键入口

- **产品范围** → [docs/01-blueprint/product/05-product-scope.md](docs/01-blueprint/product/05-product-scope.md)
- **系统架构** → [docs/01-blueprint/architecture/10-system-architecture.md](docs/01-blueprint/architecture/10-system-architecture.md)
- **领域模型** → [docs/01-blueprint/domain/07-domain-model.md](docs/01-blueprint/domain/07-domain-model.md)
- **业务规则** → [docs/01-blueprint/domain/08-business-rules.md](docs/01-blueprint/domain/08-business-rules.md)
- **Control Plane API** → [docs/02-development/api/openapi.yaml](docs/02-development/api/openapi.yaml)
- **Gateway API** → [docs/02-development/api/gateway-openapi.yaml](docs/02-development/api/gateway-openapi.yaml)
- **本地开发 Runbook** → [docs/02-development/implementation/05-bootstrap-local-development-runbook.md](docs/02-development/implementation/05-bootstrap-local-development-runbook.md)
- **生产运维 Runbook** → [docs/04-operations/v3-operational-runbook.md](docs/04-operations/v3-operational-runbook.md)
- **验收证据矩阵** → [docs/03-acceptance/02-Milestone验收证据矩阵.md](docs/03-acceptance/02-Milestone验收证据矩阵.md)

### 架构决策记录（ADR）

设计取舍的完整论证在 [docs/01-blueprint/architecture/](docs/01-blueprint/architecture/)：

| ADR | 主题 |
|---|---|
| [001](docs/01-blueprint/architecture/adr/ADR-001-modular-monolith.md) | 采用模块化单体而非微服务 |
| [002](docs/01-blueprint/architecture/adr/ADR-002-retain-raw-evidence.md) | 保留原始证据不做转换丢弃 |
| [003](docs/01-blueprint/architecture/adr/ADR-003-separate-consumption-pricing-charge.md) | 消费 / 定价 / 费用三层分离 |
| [004](docs/01-blueprint/architecture/adr/ADR-004-immutable-ledger.md) | 不可变账本与追加式更正 |
| [005](docs/01-blueprint/architecture/adr/ADR-005-v1-post-billing-first.md) | V1 先做后发账单归集 |
| [006](docs/01-blueprint/architecture/adr/ADR-006-content-blind-gateway.md) | Gateway 内容盲（不看 Prompt） |
| [008](docs/01-blueprint/architecture/adr/ADR-008-mysql-mybatis-v1.md) | MySQL + Plain MyBatis |
| [009](docs/01-blueprint/architecture/adr/ADR-009-redis-v1-runtime.md) | Redis 作为运行时支撑而非真相源 |
| [012](docs/01-blueprint/architecture/adr/ADR-012-single-repo-lightweight-github-flow.md) | 单仓库轻量 GitHub Flow |

---

## 🔬 CI 与安全

每次 PR 与 `main` / `dev` push 运行两条流水线：

```text
CI       14 个作业：脚本测试 ×2、后端单元 / 集成 / 架构、网关单元 / 集成 / 架构、
                       前端 lint / test / build、Docker build、Playwright E2E
Security CodeQL（Java · JavaScript）×2、Trivy filesystem（vuln/misconfig/secret）、
         Trivy image ×3（backend / frontend / gateway）
```

安全策略：

- 依赖漏洞 `HIGH` / `CRITICAL` **直接阻断**，不做 blanket ignore
- `.trivyignore` 当前为空（`Accepted risks: NONE`）
- 只有 CodeQL 作业拥有 `security-events: write`，流水线默认 `contents: read`
- 镜像扫描验证真实构建产物，文件系统扫描覆盖依赖清单

本地复现 Trivy 见 [CONTRIBUTING.md](CONTRIBUTING.md#11-持续安全-ci-m9--aic-079)。

---

## 🤝 贡献

流程：**Issue → 短生命周期分支 → Pull Request → CI → 人工验收 → Squash Merge → `main`**

```text
feat/*  fix/*  refactor/*  test/*  perf/*  docs/*  chore/*
```

提交信息使用 Conventional Commits：

```text
feat(iam): implement password login
fix(import): recover expired worker lease
docs(api): update budget contract
chore(ci): add backend workflow
```

任何 API 变更必须**同步** OpenAPI 契约、后端 DTO、前端类型与契约测试。数据库变更必须附带 Flyway 迁移。

详见 [CONTRIBUTING.md](CONTRIBUTING.md)。

---

## 📄 许可证

专有许可，仅限内部使用。详见 [LICENSE](LICENSE)。

---

## 📌 项目状态

```text
FINAL_PRODUCT_VERSION = V3
STABLE_RELEASE       = v3.0.0
Feature development  = CLOSED
Operational mode     = MAINTENANCE_ONLY
V4                   = NOT_PLANNED
Known release-blocking defects = NONE
```

V3 是最终功能版本，功能研发已关闭。项目进入长期维护模式，仅接受安全更新、缺陷修复、运维修复、CI / 依赖维护与文档维护，维护触发条件是**真实缺陷与实际必要性**。

发布基线：

| 版本 | 提交 |
|---|---|
| v1.0.0 | `982d06a` |
| v1.0.1 | `b96be61` |
| v1.1.0 | `102f287` |
| v2.0.0 | `93e3596` |
| v3.0.0 | `9c55125` |

变更明细见 [CHANGELOG.md](CHANGELOG.md)。
