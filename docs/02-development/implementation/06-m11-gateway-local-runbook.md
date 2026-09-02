# M11 Gateway Edge — Local Development Runbook

> 本文记录经过实际运行的 M11 Gateway 本地开发/验收步骤。M10 frozen design 是行为权威,本 runbook 只提供命令与流程;`docs/02-development/v2-detailed-design/` 与 `docs/02-development/api/gateway-openapi.yaml` 优先。

## 1. 前置条件

本地开发保持"基础设施 Docker、应用本机运行":

```text
MySQL   127.0.0.1:3307   (Docker)
Redis   127.0.0.1:6379   (Docker)
MinIO   127.0.0.1:9000/9001 (Docker)
Backend 127.0.0.1:8080   (本机 Spring Boot)
Gateway 127.0.0.1:8081   (本机 Spring WebFlux)
```

启动基础设施(如未运行):

```powershell
Set-Location "E:\project\AI-CostOps"
.\scripts\dev\start-infra.ps1
```

M11 本地秘密放在 git-ignored 的 `.env.m11`(Gateway HMAC / Request HMAC / Provider KEK / dev raw key),由 Gateway 进程环境注入,不打印、不提交。

## 2. Backend 初始化(M11 schema + dev provisioning)

Backend 是唯一 Flyway 迁移所有者。先让 Backend 以本地 dev profile 运行一次,把 V1..V18 迁移到本地 MySQL 并执行 `DevGatewayBootstrap`(idempotent provisioning):

```powershell
Push-Location backend
$env:SPRING_PROFILES_ACTIVE = "dev"
# 通过 .env.m11 或 IDE 注入 AICOSTOPS_GATEWAY_DEV_BOOTSTRAP_ENABLED=true 等
.\mvnw.cmd -B spring-boot:run
Pop-Location
```

Backend 进程启动后停止即可(Gateway 需要的 schema 与 dev 凭证已在 MySQL 中)。

## 3. Gateway 本地运行

```powershell
Push-Location gateway
$env:SPRING_PROFILES_ACTIVE = "local"
.\mvnw.cmd -B spring-boot:run
Pop-Location
```

`local` profile 组已启用 `dev` 相关组件,并允许可选 dev bootstrap;生产 profile 由 `GatewayProductionConfigurationValidator` 强制拒绝 dev bootstrap 与缺省秘密。

健康探针:

```text
GET http://localhost:8081/actuator/health/liveness
GET http://localhost:8081/actuator/health/readiness
GET http://localhost:8081/actuator/prometheus
```

## 4. 本地请求示例(仅示意,不含真实 secret)

```powershell
$key = $env:AICOSTOPS_GATEWAY_DEV_RAW_KEY
$body = '{"model":"default-chat","messages":[{"role":"user","content":"hi"}]}'
curl.exe -s -X POST http://localhost:8081/v1/chat/completions `
  -H "Authorization: Bearer $key" -H "Content-Type: application/json" `
  -H "Idempotency-Key: dev-1" -d $body
```

SSE:

```powershell
$sse = '{"model":"default-chat","messages":[{"role":"user","content":"hi"}],"stream":true}'
curl.exe -s -N -X POST http://localhost:8081/v1/chat/completions `
  -H "Authorization: Bearer $key" -H "Content-Type: application/json" `
  -H "Idempotency-Key: dev-2" -d $sse
```

状态 API:

```powershell
curl.exe -s http://localhost:8081/v1/gateway/requests/<gwr_...> -H "Authorization: Bearer $key"
```

## 5. 测试命令

单元(排除 architecture/integration tag 的非 `*IntegrationTest` 测试):

```powershell
Push-Location gateway
.\mvnw.cmd -B "-DexcludedGroups=architecture,integration" test
Pop-Location
```

架构:

```powershell
.\mvnw.cmd -B "-Dgroups=architecture" test
```

集成(真实 MySQL 8.4 + Redis,Testcontainers;ControllerStatus/Redaction 等 `@Tag("integration")` 类由 surefire 承担,`*IntegrationTest` 由 failsafe 承担):

```powershell
.\mvnw.cmd -B "-Dgroups=integration" verify
```

开发迭代中只跑 focused 测试,完整 suite 在最终验收集中跑,避免每改一次就全量启动容器。

## 6. 本地 smoke

Gateway 正常运行、本地 dev 凭证就绪后:

```powershell
.\scripts\smoke-m11-gateway.ps1 -BaseUrl "http://localhost:8081" -EnvFile ".env.m11"
```

有 `AICOSTOPS_MIMO_API_KEY` 时跑完整 Provider 路径(非流式、幂等 replay、SSE `[DONE]`、status);无 key 时记录 `BLOCKED: missing external MiMo credential`,绝不伪造 PASS。脚本不打印任何 secret。

## 7. 一次本地 Gateway 镜像验证

Dockerfile 与打包完整后的最终验证(不要在日常迭代中使用):

```powershell
docker system df
docker build --tag ai-costops-gateway:m11-local-check gateway
# 验证后只删除该 disposable 镜像
docker image rm ai-costops-gateway:m11-local-check
docker system df
```

禁止 `docker system prune` / `docker compose down -v` 等全局清理(除非显式人工授权)。完整 image matrix 交给 GitHub Actions CI/Security。

## 8. 安全边界速查

- Gateway 无 Flyway 依赖;Backend 是唯一生产迁移所有者。
- 阻塞 JDBC/MyBatis 只在专用 bounded scheduler 上运行,禁止 Reactor Netty event-loop 线程。
- `DISPATCH_INTENT` 必须持久化并 commit 之后才允许 Provider I/O;dispatch 后禁止自动重试。
- Prompt/Completion 不持久化;缺失 usage 永不补 0。
- 日志/指标/审计禁止出现 raw key、Idempotency-Key、Provider secret、KEK、prompt/completion。
- production 下 Provider endpoint 必须 HTTPS 且为 approved host(`MimoEndpointPolicy`)。