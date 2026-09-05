# AI CostOps

面向研发团队的多 AI Provider 成本归集、费用核算、预算治理、对账与账期管理平台。

当前稳定版本：**v1.1.0（M9 Production Foundation）**。V1 已完成并冻结：M0–M8、AIC-001～AIC-073、AIC-073 Final Human Acceptance / Release Sign-off、`v1.0.0` 与后续补丁 `v1.0.1` 均已完成。M9 已完成：AIC-074～AIC-083 全部验收（审计收口、生产配置加固、Metrics/Observability、Browser E2E、Security CI、Backup/Restore drill、Import/Reporting scale evidence、真实 Provider 认证），最终判决见 `docs/03-acceptance/aic-083-m9-final-acceptance.md`。`v1.1.0` 已正式发布。M10 V2 Detailed Design 也已完成并冻结，AIC-084～AIC-093 全部通过，主设计经 PR #129 squash merge 至 `main@1ed62c68c09458570c5cd04f812a2525028db7a2`。仓库当前不声称生产环境验证、完整 Provider 覆盖、FOCUS Compliance 或未实测的规模能力。

发布记录：

```text
v1.0.0 → 982d06a0e9ec844ea687ed746d6b9d8f39d86686
v1.0.1 → b96be614e2d843c101add49fe6daffb9d2343a56
v1.1.0 → 102f287da9bfc922ffaabb1b7244a973a0f813eb
```

`v1.0.1` 通过 PR #103 加固 Import lease recovery 在 MySQL deadlock race 下的 bounded retry；不改变 API、Schema 或 V1 产品范围。

## 当前里程碑状态

```text
V1 = COMPLETE / FROZEN
M9 PRODUCTION FOUNDATION = COMPLETE / ACCEPTED (see AIC-083)
AIC-074 ~ AIC-083 = PASS (082 = REAL_PROVIDER_CERTIFICATION_PASS, MiMo real export)
Current stable (published) = v1.1.0
v1.1.0 = RELEASED
M10 V2 DETAILED DESIGN = COMPLETE / FROZEN
AIC-084 ~ AIC-093 = PASS / FROZEN
M10 design merge = PR #129 / main@1ed62c68c09458570c5cd04f812a2525028db7a2
M11 GATEWAY EDGE MVP = COMPLETE / ACCEPTED
M12 IDENTITY / ATTRIBUTION / BUDGET RESERVATION = COMPLETE / ACCEPTED
M13 REALTIME METERING / SETTLEMENT = COMPLETE / ACCEPTED
M14 MULTI-PROVIDER ROUTING / RESILIENCE = COMPLETE / ACCEPTED
M14 merge baseline = PR #146 / main@a9afc8aef64b9d66608ccc19c611b703e545610b (feat(m14): deliver multi-provider routing and resilience)
M15 HYBRID RECONCILIATION = NEXT IMPLEMENTATION MILESTONE
```

M10 冻结入口：

- [V2 Detailed Design](docs/02-development/v2-detailed-design/README.md)
- [M10 Design Freeze Matrix](docs/03-acceptance/m10-design-freeze-matrix.md)
- [Gateway OpenAPI Contract](docs/02-development/api/gateway-openapi.yaml)

## V1 主链路

~~~text
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
~~~

V1 已覆盖 Provider Import、费用证据、Canonical Cost、归属、Budget / Commitment、Approval、Ledger、Reconciliation、Billing Period、审计查询和 Workbench 基础能力。

## Local Development

### Daily Development Default（日常开发默认模式，AI agent 必须遵守）

Daily Development 只用 Docker 运行基础设施（MySQL / Redis / MinIO），Backend / Gateway / Frontend 三个应用进程全部在本机原生运行。

```text
Daily Development Default

Docker:
- MySQL
- Redis
- MinIO

Native:
- Backend
- Gateway
- Frontend
```

明确声明：Backend / Frontend / Gateway 的 Docker 镜像可以存在，但它们 **不是** 日常本地开发的默认运行时（`Backend / Frontend / Gateway Docker images may exist, but they are NOT the default local development runtime.`）。日常 edit-test 循环不要执行 `docker compose build` / `docker compose up --build` / `docker build backend|frontend|gateway`。

当前本地仓库路径：

```text
E:\project\AI-CostOps
```

```text
Frontend      http://localhost:5173    （Vite，本机，HMR）
Backend       http://localhost:8080    （Spring Boot，本机）
Gateway       http://localhost:8081    （Spring WebFlux，本机）
MySQL         localhost:3307          （Docker）
Redis         localhost:6379          （Docker）
MinIO         localhost:9000 / 9001   （Docker API / Console）
```

启动（先起基础设施，再分别本机启动三个应用进程）：

```powershell
Set-Location "E:\project\AI-CostOps"
.\scripts\dev\start-infra.ps1
cd backend && .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
cd gateway && $env:SPRING_PROFILES_ACTIVE = "local"; .\mvnw.cmd spring-boot:run
cd frontend && npm run dev
```

详细说明见 [docs/02-development/implementation/05-bootstrap-local-development-runbook.md](docs/02-development/implementation/05-bootstrap-local-development-runbook.md)。

Gateway 已真实存在并作为 Daily Development 的三个本机应用进程之一运行（Frontend / Backend / Gateway）。Gateway 本机启动、测试与 smoke 命令见 [docs/02-development/implementation/06-m11-gateway-local-runbook.md](docs/02-development/implementation/06-m11-gateway-local-runbook.md)。

## 本地完整 Compose Quick Start

> ⚠️ **HARD WARNING — Full Compose is NOT the default Daily Development mode.**
>
> 本段落描述的完整 Compose 栈（含 backend / frontend / gateway 容器镜像的 build 与 up）**只**用于以下场景：
>
> - full integration（最终集成验证）
> - E2E
> - smoke
> - release validation
> - explicit Docker validation
>
> **AI agents MUST NOT use this flow as the routine edit-test loop.**
>
> 日常开发循环中**禁止**反复执行：
>
> ```text
> docker compose build
> docker compose up --build
> docker build backend
> docker build frontend
> docker build gateway
> ```
>
> 日常开发请改用：
>
> - Docker 只运行 MySQL / Redis / MinIO（`scripts/dev/start-infra.ps1`）
> - Backend / Gateway / Frontend 三个进程本机原生运行
>
> 注意：CI / Security 流水线中的 Docker build 属于合法验证流程，不受上述日常循环禁令约束。

PowerShell：

~~~powershell
Copy-Item .env.example .env
docker compose --env-file .env build
docker compose --env-file .env up -d
docker compose --env-file .env ps
~~~

默认浏览器入口为 `http://localhost:8080`，后端通过 Nginx 的同源 `/api/v1` 反向代理访问。健康检查：

~~~powershell
Invoke-WebRequest http://localhost:8080 -UseBasicParsing
docker compose --env-file .env exec backend curl -fsS http://localhost:8080/actuator/health/liveness
~~~

`.env.example` 使用 local-only 占位值。它开启仅限 `dev` profile 的开发 bootstrap：容器启动后创建本地合成管理员、当前自然月 OPEN billing period，并从环境变量读取密码；密码不会写入日志。生产环境必须关闭 `AICOSTOPS_DEV_BOOTSTRAP_ENABLED` 并使用正式身份、Secret 与账期流程。

如果默认端口 8080 已被本机其他进程占用，可使用一次性覆盖：

~~~powershell
$env:FRONTEND_PORT = '18080'
$env:AICOSTOPS_ALLOWED_ORIGINS = 'http://localhost:18080'
docker compose --env-file .env up -d
~~~

完成后保留数据地停止：

~~~powershell
docker compose --env-file .env down
~~~

只有明确需要销毁本项目数据库、Redis 和 MinIO 数据时才使用 `docker compose ... down -v`；不要使用全局 prune。

## V1 Smoke / Release Evidence

~~~powershell
.\scripts\smoke-v1.ps1 -EnvFile .env.example -BaseUrl http://localhost:8080/api/v1
~~~

脚本会在有界超时内检查五个 Compose 服务、MySQL / Redis / MinIO readiness、Flyway、真实登录与权限、Workbench、合成 DeepSeek import / confirm、费用证据提交和 audit query，并输出 `SMOKE_V1_PASS`。它不会打印密码、token 或 Provider key，也不会执行全局清理。完整历史证据见 [docs/superpowers/specs/2026-08-23-m8-compose-smoke.md](docs/superpowers/specs/2026-08-23-m8-compose-smoke.md)、[docs/03-acceptance/v1-release-candidate-evidence.md](docs/03-acceptance/v1-release-candidate-evidence.md) 与 [docs/03-acceptance/aic-073-final-human-acceptance.md](docs/03-acceptance/aic-073-final-human-acceptance.md)。

## Validation Status

以下 V1 数字是冻结历史证据，不因进入 V2 而改写：

- Browser UAT：32/32 scenarios passed
- State branches：14/14 passed
- P0/P1：0
- Compose Smoke：PASS
- Backend tests：437 unit + 800 integration + 34 architecture = PASS
- Frontend tests：432 Vitest + lint = PASS
- PR #103：7/7 CI PASS；合并后的 `main@b96be61` push CI PASS

M10 设计 PR #129 在最终 head 上 CI / Security 全绿；squash merge 后的 `main@1ed62c68c09458570c5cd04f812a2525028db7a2` 再次通过 8/8 CI jobs（含 Backend Integration 与 Browser E2E）以及 Trivy + CodeQL Java/Kotlin + CodeQL JavaScript/TypeScript。

Validated V1 flows：Import lifecycle, Cost allocation, Budget commitment, Expense workflow, Ledger posting, Reconciliation, Period close, CLOSED write protection, Reopen。

## Known Limitations

- SMTP delivery requires external mail service. Local development uses file-backed mailbox by default.
- Import scale measured by M9 (AIC-081): 10k / 100k / 500k import all PASS（412→434→442 rows/s，DB-backed worker，无 broker）；reporting 10k / 100k PASS；500k reporting fixture 未在本机 7.6 GB Docker 环境完成（documented non-blocking limitation，见 `docs/03-acceptance/m9-scale-evidence.md`）。
- provider-account / allocation-rule audit 审计 producer 缺口已由 AIC-074 关闭。
- Production mail integration is environment-specific and not included in V1 scope.

## Provider 支持边界

| Provider | Adapter / canonical mapping | Fixture / test status |
|---|---|---|
| DeepSeek | 已实现 | 合成 Compose smoke、M7 E2E、M8 benchmark |
| MiMo | 已实现 | fixture / adapter tests |
| Kimi | 已实现 | fixture / adapter tests |
| GLM | 已实现 | fixture / adapter tests |
| OpenAI | 已实现 | fixture / adapter tests |

M8 的真实 Compose smoke 和 benchmark 使用合成 DeepSeek export。M9（AIC-082）完成了一次真实 MiMo 导入认证（real-but-redacted，`REAL_PROVIDER_CERTIFICATION_PASS`，见 `docs/03-acceptance/m9-provider-certification-mimo.md`）；其余 Provider 未被解释为已完成真实生产导入认证。

MiMo 是 M11 Gateway Edge MVP 首个接入的 Provider Adapter，M11 验收证据见 [m11-gateway-edge-evidence.md](docs/03-acceptance/m11-gateway-edge-evidence.md)；M14 已完成 Multi-provider Routing / Resilience（PR #146）。各 Provider 的实时 streaming / financial-metering 认证边界以对应里程碑的验收证据为准。

## V2 冻结架构原则

```text
Monorepo
├─ frontend/   React / TypeScript Admin UI
├─ backend/    Java / Spring MVC Control Plane
└─ gateway/    Java / Spring WebFlux + Reactor Netty Data Plane
```

核心规则：

```text
one monorepo / two deployables / one final financial truth
MySQL = durable identity + financial truth + monetary reservation correctness
Redis != financial truth and cannot independently authorize monetary spend
Gateway = request / route / usage / reservation fact writer
CostOps Core = final Settlement / Ledger / Budget Actual / Commitment writer
DISPATCH_INTENT must be durable before potentially billable Provider I/O
missing usage != zero cost
POSTED Ledger immutable / corrections append-only
```

## 技术基线

Java 21 / Spring Boot 4.1 / Plain MyBatis / Flyway / MySQL 8.4 LTS / Redis / MinIO / React 19 / TypeScript / Vite / TanStack Query / Ant Design / ECharts / Docker Compose / Nginx / GitHub Actions。

架构、领域约束与机器可读 API 基线分别见 [docs/01-blueprint/README.md](docs/01-blueprint/README.md)、[docs/02-development/README.md](docs/02-development/README.md)、Control Plane [openapi.yaml](docs/02-development/api/openapi.yaml) 和 Gateway [gateway-openapi.yaml](docs/02-development/api/gateway-openapi.yaml)。

## 日常开发与测试

日常开发只启动 MySQL / Redis / MinIO，见 [docs/02-development/implementation/05-bootstrap-local-development-runbook.md](docs/02-development/implementation/05-bootstrap-local-development-runbook.md)。

后端（Windows PowerShell）：

~~~powershell
cd backend
.\mvnw.cmd -B "-DexcludedGroups=architecture,integration" test
.\mvnw.cmd -B "-Dgroups=architecture" test
.\mvnw.cmd -B "-Dgroups=integration" verify
~~~

前端：

~~~powershell
cd frontend
npm ci
npm run lint
npm test -- --run
npm run build
~~~

Gateway（本机 WebFlux 数据面）：

~~~powershell
cd gateway
$env:SPRING_PROFILES_ACTIVE = "local"
.\mvnw.cmd -B spring-boot:run
~~~

Gateway 本机启动、测试、smoke 与验证命令见 [06-m11-gateway-local-runbook.md](docs/02-development/implementation/06-m11-gateway-local-runbook.md)；M11 验收证据见 [m11-gateway-edge-evidence.md](docs/03-acceptance/m11-gateway-edge-evidence.md)。

## 文档与验收

验收入口为 [docs/03-acceptance/README.md](docs/03-acceptance/README.md)。M8 专项证据包括 AIC-066 schema / query review、AIC-067 import benchmark、AIC-068 financial concurrency、AIC-069 runtime failure injection、AIC-070 security review，以及 AIC-071 Compose smoke / AIC-072 RC evidence / AIC-073 final sign-off。

V1 的 RC、验收与 benchmark 文档作为冻结历史证据保留；后续不得把历史时间点状态改写成当前状态。

M10 最终设计验收见 [m10-design-freeze-matrix.md](docs/03-acceptance/m10-design-freeze-matrix.md)。

## V2 方向

V1 已关闭，M10 V2 Detailed Design 已冻结，M11–M14 已全部完成并验收。当前进入 **M15 — Hybrid Reconciliation** 实施规划与开发阶段。

现有产品/设计入口：

- [Product Scope](docs/01-blueprint/product/05-product-scope.md)
- [V1 → V2 Roadmap](docs/01-blueprint/product/11-roadmap.md)
- [V2 Detailed Design](docs/02-development/v2-detailed-design/README.md)
- [Gateway OpenAPI](docs/02-development/api/gateway-openapi.yaml)

M11–M14 已按 M10 冻结契约分里程碑交付（各自验收证据见 [docs/03-acceptance/README.md](docs/03-acceptance/README.md)）；M15 Hybrid Reconciliation 同样必须服从冻结设计的语义边界，不得越界实现。

## Git 协作

~~~text
Issue → Short-lived Branch → Pull Request → CI → Human Acceptance → Squash Merge → main
~~~

当前 V1 状态：`COMPLETE / FROZEN`。M9 状态：`COMPLETE / ACCEPTED`。M10 状态：`COMPLETE / FROZEN`。M11–M14 状态：`COMPLETE / ACCEPTED`。当前稳定（已发布）版本：`v1.1.0`。M14 merge baseline：`PR #146 / main@a9afc8aef64b9d66608ccc19c611b703e545610b`。下一实现里程碑：`M15 — Hybrid Reconciliation`。
