# scripts/ 目录说明与归档策略

本目录承载项目的运维与验收脚本。项目自 V3.0.0 起处于 **MAINTENANCE_ONLY** 阶段，
为避免历史交付痕迹与日常工具混用，约定如下。

## 目录职责

| 路径 | 性质 | 说明 |
|---|---|---|
| `tasks.mjs` | **日常（活跃）** | 跨平台任务运行器，根 `package.json` 的唯一入口（`npm run dev/build/test/verify/infra:*`） |
| `dev/` | **日常（活跃）** | 基础设施命令的 `.ps1` / `.sh` 薄包装，共同调用 `tasks.mjs`，不复制业务逻辑 |
| `lib/` | **日常（活跃）** | 任务运行器的进程管理、本地配置与隔离构建辅助函数 |
| `tests/` | **日常（活跃）** | Node 内置测试，检查命令范围、失败传播、进程树清理及配置边界 |
| `ops/` | 运维（活跃） | 备份/恢复演练。变更须同步 `docs/04-operations/v3-operational-runbook.md` |
| `m16/`, `m21/` | **里程碑（冻结）** | 历史验收脚本，与 `docs/03-acceptance/` 证据矩阵一一对应，**只读保留，不得改名或移动**（文档引用路径已冻结） |
| 根级 `*-smoke*.ps1`、`m16-*.ps1` | **里程碑（冻结）** | 同上；不再新增此类命名 |

## 日常命令

在仓库根目录使用 Node 24+、JDK 21 和 npm。根 `package.json` 无依赖，不需要在根目录执行 `npm install`。

| 命令 | 行为 |
| --- | --- |
| `npm run setup` | 在 frontend 执行 `npm ci`，仅首次使用或依赖变更时需要 |
| `npm run doctor` | 检查 Node、Java、npm、Docker 引擎、Maven wrapper、前端依赖、本地配置与三端端口 |
| `npm run status` | 检查三端端口及 HTTP；端口空闲不代表进程故障，HTTP 通过不证明监听者的项目身份 |
| `npm run infra:up` | 当前 Compose 项目的 MySQL、Redis、MinIO `up -d --wait`，不停止应用容器 |
| `npm run infra:status` | 查看当前 Compose 项目的三项基础设施 |
| `npm run infra:down` | `stop mysql redis minio`，保留容器和数据卷 |
| `npm run dev` | 本机启动 Backend、Gateway（显式 local profile）与 Vite；最多等待 180 秒直到三端 HTTP 就绪 |
| `npm run build` | 两个 Maven 模块 package（跳过测试），然后前端生产 build |
| `npm run test` | 脚本测试、两个 Maven 模块单元及架构测试、全部前端单元测试；集成测试仍由 Maven 原配置分离 |
| `npm run verify` | 脚本单元与生命周期测试、两个 Maven 模块完整 verify（单元、架构、集成、打包/repackage），再执行前端测试、lint、生产 build（含 TypeScript 检查） |
| `npm run test:scripts` | 只运行任务运行器的 Node 测试，无 npm 测试库依赖 |
| `npm run test:scripts:integration` | 需要 Docker：用隔离的 HTTP 进程及临时 Compose 项目验证真实命令入口、进程清理和数据卷保留；不是完整三端产品 smoke |

`dev` 在前台运行。**在启动它的终端按 Ctrl+C** 停止本次创建的三个应用进程树；其中一个提前退出或启动期间健康检查超时，也会清理其余进程。初次就绪后不持续轮询 HTTP；进程提前退出仍会被检测。基础设施继续运行，需要停止时单独执行 `infra:down`。脚本不按端口或进程名称终止其他实例，也不维护另一个后台应用守护进程。

`setup/dev/build/test/verify/infra:*` 支持 `--dry-run`：例如 `npm run dev -- --dry-run`。它只输出命令计划，不启动进程、不创建 POM 或 `.env`。`doctor/status` 是实际只读检查。

### 本地配置与运行方式

`infra:*` 的实际执行必须有根 `.env`，先复制 `.env.example` 并配置本地参数。脚本不会自动复制、覆盖配置，也不会把配置值输出到环境检查中。Docker 不可用时请启动 Docker Desktop **主程序**，等待引擎就绪；任务运行器不会启动 Windows Docker 服务。

配置优先级是当前进程环境变量 > 根 `.env` > local profile 的默认值。`.env` 解析为标准 dotenv 字面值，**不执行变量插值或 shell 表达式**。原有 `SPRING_DATASOURCE_*`、`AICOSTOPS_REDIS_*` 与 `AICOSTOPS_STORAGE_*` 优先；未指定时，将 `.env` 的 MySQL、Redis、MinIO 配置映射为主机 localhost 地址及已发布端口。原有独立容器可以继续使用，但 `infra:*` 只管理当前 Compose 项目的容器；如果端口被独立容器或其他程序占用，`infra:up` 会拒绝启动。

本机三端端口分别用 `AICOSTOPS_BACKEND_PORT`（8080）、`AICOSTOPS_GATEWAY_PORT`（8081）、`AICOSTOPS_FRONTEND_PORT`（5173）覆盖。它们独立于 Compose 的 `FRONTEND_PORT/BACKEND_PORT`。Vite 代理随本机 Backend 端口调整，local 登录 Origin 默认包含 localhost 和 127.0.0.1 的 Vite 地址；特殊本地域名用 `AICOSTOPS_LOCAL_ALLOWED_ORIGINS`。local 的 cookie 使用非 Secure HTTP 模式。

### 构建产物与测试报告

根任务命令的 Maven 输出在 `.local-dev/builds/<命令>-<时间>-<唯一标识>/<模块>/target/`，启动时打印完整路径。保留 Maven 的标准 target 目录形状，让架构测试正确排除 test-classes。源文件、资源、插件和测试范围沿用模块 POM，只用同模块目录下的临时 POM 指定输出目录；结束后移除临时 POM。程序崩溃留下的 POM 已 gitignore，不会被自动当作下次配置。

这样构建不会覆盖 `backend/target` 或 `gateway/target` 中正在运行的 JAR。根命令生成的新 JAR 在打印的独立路径下，测试报告位于其 surefire-reports/failsafe-reports；直接进入模块执行 `mvnw` 仍沿用原 target。完整 verify 需要 Docker/Testcontainers，会比单元测试明显更慢。

## 新脚本约定

1. **命名**：使用语义化名称（如 `backup-mysql.ps1`），禁止再以里程碑编号命名目录或文件（`m23-*`、`v4-*` 等）。
2. **平台**：日常使用的脚本必须同时提供 `.ps1` 与 `.sh`；一次性/运维演练脚本可仅 `.ps1`，但须在头部注释标明平台约束。
3. **归宿**：日常工具进 `dev/` 或 `ops/`；验收证据类脚本随里程碑结束后归档为冻结状态，并在对应验收文档中登记路径。
4. **入口优先**：能被任务运行器覆盖的通用流程（构建、测试、基础设施）优先加入 `tasks.mjs`，不再新增独立脚本。

## 前端历史命名说明

前端样式中的 `m6-*` / `v3-*` 类名前缀（`styles.css`、`features/intelligence/v3-tokens.css` 等）
是 M6 / V3 里程碑的交付痕迹。考虑到维护阶段重命名的 churn 与回归风险高于收益，
这些前缀**冻结保留**；新增样式请使用语义化命名（如 `settings-*`、`fin-*`），
设计令牌统一以 `styles.css` 的 `:root` 变量为准。
