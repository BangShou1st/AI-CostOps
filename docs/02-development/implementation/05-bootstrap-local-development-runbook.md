# 05. Bootstrap 与本地开发 Runbook

## 1. Clone

两个人：

```bash
git clone <AI-CostOps repository>
cd AI-CostOps
```

检查：

```bash
git config user.name
git config user.email
```

必须使用各自真实 GitHub 身份对应信息。

## 2. 本地环境变量

```bash
cp .env.example .env
```

Windows PowerShell：

```powershell
Copy-Item .env.example .env
```

`.env` 只保存本地配置并保持 ignored。

## 3. 推荐日常开发模式 — Daily Development Mode

日常开发时 Docker 只运行基础设施（MySQL / Redis / MinIO），Backend 与 Frontend 直接在 Windows 本机运行，全程不执行任何 `docker build`。

前置条件：仓库根目录存在 `.env`（首次执行 `Copy-Item .env.example .env`）。

Terminal 1 — Docker 基础设施（幂等、不构建镜像、不删 volume）：

```powershell
.\scripts\dev\start-infra.ps1
# 等价于：docker compose -f compose.yaml -f compose.dev.yaml stop backend frontend
#         docker compose -f compose.yaml -f compose.dev.yaml up -d mysql redis minio
```

`start-infra.ps1` 同时负责从 Full Integration 切回 Daily Mode：若 backend /
frontend 容器仍处于运行状态，脚本会先安全 `stop` 它们（只停止，不 `rm`、不删
volume），再启动基础设施。一次执行后 Docker 中应只剩 mysql / redis / minio。

Terminal 2 — Spring Boot 后端（本机）：

```powershell
cd backend
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local
```

必须带 `local` profile（`backend/src/main/resources/application-local.yml`）：它把 MySQL / Redis / MinIO 指向 compose.dev.yaml 暴露的 localhost 端口（3307 / 6379 / 9000），并提供本地 JWT 密钥与开发开关（允许公开注册、非 secure refresh cookie）。默认监听 `http://localhost:8081`。

IDE 用户：Run Configuration 设置 `active profiles = local` 即可，不需要手工复制十几个环境变量；`application-local.yml` 的每个值都支持同名环境变量覆盖（规则与 `application.yml` 一致）。

Terminal 3 — Vite 前端（本机，HMR）：

```powershell
cd frontend
npm ci        # 首次或依赖变更时
npm run dev
```

Vite dev server 监听 `http://localhost:5173`，并把 `/api/v1` 代理到本机 Backend（默认 `http://localhost:8081`，可用环境变量 `BACKEND_PORT` 覆盖）。浏览器视角保持同源，没有额外 CORS hack，登录 / refresh cookie / 权限流程与容器模式一致。

默认端口一览（来自 `.env.example` 与 compose.dev.yaml）：

```text
Frontend dev server   http://localhost:5173   （Vite，本机，HMR）
Backend               http://localhost:8081   （Spring Boot，本机，BACKEND_PORT）
MySQL                 localhost:3307          （Docker）
Redis                 localhost:6379          （Docker）
MinIO API / Console   localhost:9000 / 9001   （Docker）
```

停止 / 查看状态：

```powershell
.\scripts\dev\stop-infra.ps1    # 只 stop mysql/redis/minio，保留所有 volume
.\scripts\dev\status.ps1
```

脚本不 build、不 prune、不删 volume，可重复执行。

## 4. 完整集成模式 — Full Integration Mode

仅用于需要完整容器环境的场合：PR 前完整 smoke test、Dockerfile 验证、CI、部署路径验证。

```bash
docker compose up -d --build
```

启动：

```text
frontend/nginx
backend
mysql
redis
minio
```

浏览器只访问前端，同源 `/api/v1` 反代后端。

默认端口：

```text
Frontend  http://localhost:8080
Backend   容器网络内 8080（compose.dev.yaml 可映射到 localhost:8081）
MySQL     compose.dev.yaml 映射到 localhost:3307
Redis     compose.dev.yaml 映射到 localhost:6379
MinIO     compose.dev.yaml 映射到 localhost:9000 / 9001
```

注意：`docker compose up -d --build` 会真实构建 backend / frontend 两张镜像并产生 BuildKit cache，不是日常开发方式；日常开发请使用第 3 节的 Daily Development Mode。

### M8 V1 RC Compose Smoke

PR3 的完整 Compose 验收使用仓库根目录的 `.env.example`，不依赖人工 SQL、手工密码或外部 Provider：

```powershell
docker compose --env-file .env.example down -v --remove-orphans
docker compose --env-file .env.example build
$env:FRONTEND_PORT = '8080'
$env:AICOSTOPS_ALLOWED_ORIGINS = 'http://localhost:8080'
docker compose --env-file .env.example up -d
.\scripts\smoke-v1.ps1 -EnvFile .env.example -BaseUrl http://localhost:8080/api/v1 -TimeoutSeconds 180
```

如果本机 8080 已被其他程序占用，可把两个环境变量和 `BaseUrl` 一起改成同一个未占用端口，例如 `18080`。Smoke 会验证服务健康、依赖 readiness、Flyway V1→V17、dev bootstrap 登录、Workbench、合成 DeepSeek import / confirm、费用证据提交和 audit query；它会在失败时退出且不会执行全局 prune。完整记录见 `docs/superpowers/specs/2026-08-23-m8-compose-smoke.md`。

`.env.example` 中的 dev bootstrap 仅用于本地 RC 验收：密码必须通过环境变量提供，不应写入日志或提交真实凭据。生产环境关闭 `AICOSTOPS_DEV_BOOTSTRAP_ENABLED`。

## 5. 自动测试数据库

Integration Test 使用 Testcontainers。

不要让测试依赖某个人电脑上长期存在的 MySQL 数据。

## 6. 后端命令

预期：

```bash
cd backend
./mvnw test
./mvnw verify
```

Windows PowerShell 使用 `.\mvnw.cmd test` 与 `.\mvnw.cmd verify`。

M0 CI 的独立入口：

```bash
./mvnw -B -DexcludedGroups=architecture,integration test
./mvnw -B -Dgroups=integration verify
./mvnw -B -Dgroups=architecture test
```

## 7. 前端命令

```bash
cd frontend
npm ci
npm run lint
npm test -- --run
npm run build
```

Node.js 固定为 24.14.0，以保持本地、Docker 与 CI 的干净安装可复现。

## 8. 开始 Issue 前

```bash
git switch main
git pull --ff-only origin main
git switch -c <branch>
```

先读：

```text
Issue
相关 Detailed Design
相关 INV-* 不变量
```

## 9. Push 前

```bash
git status
git diff
git diff --staged
```

检查有没有：

```text
.env
Secret
真实 Provider Export
target/
node_modules/
dist/
IDE 文件
DB dump
异常大 Binary
```

## 10. Provider Fixture

真实研究原件留在仓库外。

进入 Git 的 Fixture：

```text
保持真实 Schema
ID 改为 Synthetic
API Key 改为 Fake/Masked
金额根据测试目的 Synthetic
```

并标注：

```text
REAL_SCHEMA_SANITIZED
OFFICIAL_SCHEMA_SYNTHETIC
SYNTHETIC_ENTERPRISE
```

## 11. 数据库变更

禁止：

> 本地手工 ALTER 完就算了。

正确：

```text
新 Flyway Migration
→ Clean MySQL Integration Test
→ PR
```

## 12. Redis Debug

本地可以 Flush Redis，但预期只能导致：

```text
Session / Cache 丢失
```

如果 Ledger/Budget/BillingPeriod 受到影响，就是实现 Bug。

## 13. 重置本地数据

以后可以提供：

```text
scripts/dev/reset-local-data.*
```

脚本必须显式警告会删除 Volume，不允许隐藏破坏性操作。

## 14. 跨平台

`.gitattributes` / `.editorconfig` 统一文本。

Shell Script 使用 LF。

如果 Windows/Linux 脚本差异明显，文档提供等价命令，不提交个人环境 Hack。

## 15. M0 Bootstrap 验收

两个人都能从同一个 main 独立完成：

```text
clone
配置 .env
backend build
frontend build
基础设施启动
测试
完整 Compose 启动
```

才算 Repository Foundation 真正成立。

当前实现版本：

```text
Spring Boot 4.1.0
mybatis-spring-boot-starter 4.1.0
MyBatis Core 3.5.19
React 19.2.8
TypeScript 6.0.3
Vite 8.2.1
MySQL 8.4
Redis 8.8.1
```

真实执行证据见 `docs/03-acceptance/implementation/08-m0-foundation-evidence.md`。

## 16. 可选维护（非日常流程）

以下操作都不是日常开发的一部分；Daily Development 模式不产生 backend / frontend
构建缓存，正常不需要执行。

偶尔需要回收磁盘时：

```powershell
docker builder prune -f
```

注意区分两种资源：

```text
RAM  ：运行中的 WSL / Docker / Java / Node 进程占用，删镜像不影响 RAM。
Disk ：Docker images / layers / BuildKit cache / WSL 虚拟磁盘占用。
```

删除镜像或清理 cache 后，Docker Desktop（WSL2 后端）的虚拟磁盘文件
（Settings → Resources → Advanced → Disk image location）不会自动收缩，
需要手动压缩才能把空间还给 Windows：

```powershell
# 1. 退出 Docker Desktop，然后
wsl --shutdown
# 2. 管理员 PowerShell，路径替换为 Docker Desktop 设置中的实际 vhdx 路径
Optimize-VHD -Path "<DockerData>\wsl\disk\docker_data.vhdx" -Mode Full
# 若系统没有 Hyper-V 模块，可用 diskpart：select vdisk file=... / attach vdisk readonly / compact vdisk
```

严禁把 `docker system prune -a`、`docker volume prune`、`docker compose down -v`
当作日常流程；业务数据 volume 不得自动删除。
