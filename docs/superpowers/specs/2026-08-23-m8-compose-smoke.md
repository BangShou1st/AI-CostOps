# M8 Stage 2 / PR3 — AIC-071 Stable Docker Compose Smoke

- 日期：2026-08-23
- 分支：release/m8-v1-rc
- 起始提交：a076b43f159bbe4bd0d481287d104783e5051a1b
- 范围：AIC-071；为 AIC-072 V1 RC 提供可复核的容器启动、认证、导入、费用证据和重启持久化证据

## 1. 验收边界

本记录覆盖真实 Docker Compose 全栈：

- MySQL、Redis、MinIO、backend、frontend 五个服务；
- 空数据库 Flyway V1→V17；
- 依赖 readiness、backend liveness、frontend HTTP；
- dev-only bootstrap 产生的本地合成 operator；
- 真实登录、组织上下文、权限和 Workbench；
- 合成 DeepSeek ZIP 上传、异步 worker、READY_FOR_REVIEW、confirm 和 canonical charge read；
- 员工费用草稿、证据上传、提交和 audit query；
- 不删除 volume 的重启 / re-up 与数据读取。

本 smoke 故意在 canonical import confirmation 与 expense submission 后停止。M7 的 DeepSeek provider E2E 另行覆盖 allocation、posting、reconciliation、close；M7 expense E2E 另行覆盖 finance approval 到 close。AIC-071 不重复声称这些更深链路由 Compose smoke 覆盖。

## 2. 环境与启动基线

| 项目 | 值 |
|---|---|
| OS | Windows 11 Home 中文版 build 26200，amd64 |
| Docker Desktop | 4.82.0 (233772) |
| Docker Engine | 29.6.1 |
| Docker Compose | v5.3.0 |
| Compose project | ai-costops |
| 最终 smoke 入口 | http://localhost:18080 |
| 默认入口 | http://localhost:8080 |

仓库 Compose 定义了 mysql、redis、minio、backend、frontend，使用 network ai-costops-network，并声明：

- ai-costops_mysql-data
- ai-costops_redis-data
- ai-costops_minio-data
- ai-costops_dev-mailbox-data

五个服务均有健康检查；frontend 通过 Nginx 将 /api/v1/ 反代到 backend:8080。compose.dev.yaml 是日常开发 overlay，额外发布 MySQL、Redis、MinIO 端口；本次使用完整 compose.yaml。

初次使用默认端口时，发现本机另一个 E:\spec-agent\backend Java 进程已占用 8080。未终止或修改该进程；最终复验使用一次性 FRONTEND_PORT=18080 与 AICOSTOPS_ALLOWED_ORIGINS=http://localhost:18080。这不是仓库服务故障。

dev bootstrap 位于 dev profile，密码只从环境读取且不写日志。它创建本地合成 operator、验收所需角色和当前自然月 OPEN billing period；不使用绕过应用权限的直接 SQL。

## 3. Fresh volume run

清理范围仅为本 Compose project：

~~~powershell
docker compose --env-file .env.example down -v --remove-orphans
docker compose --env-file .env.example build
$env:FRONTEND_PORT = '18080'
$env:AICOSTOPS_ALLOWED_ORIGINS = 'http://localhost:18080'
docker compose --env-file .env.example up -d
.\scripts\smoke-v1.ps1 -EnvFile .env.example -BaseUrl http://localhost:18080/api/v1 -TimeoutSeconds 180
~~~

没有使用 docker system prune、docker volume prune 或其他全局清理。

结果：

| 检查 | 结果 |
|---|---|
| backend / frontend image build | PASS |
| 五个 Compose service healthy | PASS |
| MySQL SELECT 1 | PASS |
| Redis PING | PASS |
| MinIO /minio/health/ready | PASS |
| backend internal liveness | PASS |
| frontend HTTP 200 | PASS |
| blocker-pattern log scan | PASS；未发现启动 blocker |
| script terminal marker | SMOKE_V1_PASS |

日志中的 MySQL self-signed CA、MySQL insecure pid-file 和 RedisBloom bf-error-rate 配置提示被分类为环境 / 镜像提示，不是启动 blocker；没有把它们隐藏。

## 4. Flyway 与服务启动

fresh volume 上 backend 日志确认：

- Successfully validated 17 migrations
- current schema version 17
- Schema 17 is up to date. No migration necessary.（重启后）

数据库查询确认 17 条成功 migration，按 installed_rank 的最新版本为 V17；V17 为 V17__m8_budget_lookup_index.sql。重启后没有重复 migration 或 schema failure。

## 5. 应用级 smoke

脚本在不打印密码、JWT 或 synthetic Provider key 的情况下完成：

| 流程 | 结果 |
|---|---|
| dev bootstrap login | PASS；expiresIn=900 |
| /auth/me organization / permissions | PASS |
| Workbench schema | PASS |
| 创建 DEEPSEEK provider account | PASS |
| synthetic DeepSeek ZIP upload | PASS |
| worker 状态 READY_FOR_REVIEW | PASS |
| confirm import | PASS |
| canonical DEEPSEEK charge read | PASS |
| employee expense create DRAFT | PASS |
| synthetic evidence upload | PASS |
| expense submit SUBMITTED | PASS |
| org-scoped audit query | PASS；totalElements >= 1 |

首次 fresh run 在 import confirm 处暴露真实配置缺口：当前月份没有覆盖交易时间的 billing period，API 返回 409。修复为 dev-only bootstrap 在同一事务中幂等创建当前自然月 OPEN period 后，重新 fresh volume、build、up 和 smoke 全部通过。该修复没有放宽财务层的账期校验。

## 6. Restart / re-up persistence

不删除 volume 的重启命令：

~~~powershell
docker compose --env-file .env.example down
$env:FRONTEND_PORT = '18080'
$env:AICOSTOPS_ALLOWED_ORIGINS = 'http://localhost:18080'
docker compose --env-file .env.example up -d
~~~

重启后五个服务再次 healthy，backend 日志显示 schema 17 已是最新。应用层读取结果：

- /auth/me organization id = 1
- Workbench schema = true
- confirmed imports = 1
- submitted expenses = 1
- available evidence = 2
- first available evidence download = HTTP 200, 45 bytes

MySQL persistence aggregate 为：

~~~text
successful_migrations:17
latest_installed_version:17
confirmed_imports:1
submitted_expenses:1
available_evidence:2
open_billing_periods:1
audit_events:6
~~~

这些读取证明业务数据、证据对象、schema 和开发账期在容器 re-up 后仍可用。

## 7. 自动化入口

实现脚本：[scripts/smoke-v1.ps1](../../../scripts/smoke-v1.ps1)。

它具备有界 readiness polling、服务级健康检查、依赖探针、日志 blocker 扫描、应用级 happy path 和明确的 PASS marker；失败即退出，临时合成文件在 finally 中清理，不执行全局 Docker 清理。

## 8. AIC-071 结论

**PASS**。在 Windows Docker Desktop 环境中，完整 Compose、空库迁移、应用级认证 / Workbench、合成 DeepSeek 导入、费用证据提交、audit query 和不删 volume 的重启持久化均有通过证据。AIC-071 不包含生产部署、不包含真实 Provider credential、不包含 AIC-073，也不替代 M7 的财务深链路 E2E。
