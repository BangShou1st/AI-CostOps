# 01 — Production Configuration（AIC-075）

> M9 / v1.1 Production Foundation。目标：生产 profile 对 dev-only / 弱 / 不安全配置
> fail-fast，并在文档中固化 TLS 边界，不引入 Kubernetes。

## 1. 范围

- 新增 `backend/src/main/resources/application-prod.yml`：deny-by-default 策略默认值，无任何凭据。
- 新增 `com.aicostops.config.ProductionConfigurationValidator`：仅 `prod` profile 激活，
  启动时（`InitializingBean#afterPropertiesSet`）对解析后的配置做 fail-fast 校验。
- TLS 边界文档：`deploy/production/README.md`。

## 2. Fail-fast 校验规则

| 环境变量 | 解析属性 | prod 要求 |
|---|---|---|
| `AICOSTOPS_JWT_SIGNING_KEY` | `aicostops.auth.jwt-signing-secret` | 非空、≥32 字符，且不得以仓库公开的 `local-development-only-signing-key` 开头 |
| `AICOSTOPS_DEV_BOOTSTRAP_ENABLED` | `aicostops.auth.dev-bootstrap-enabled` | 必须 `false` |
| `AICOSTOPS_ALLOW_PUBLIC_REGISTRATION` | `aicostops.auth.allow-public-registration` | 必须 `false`（除非显式评审允许策略） |
| `AICOSTOPS_REFRESH_COOKIE_SECURE` | `aicostops.auth.refresh-cookie-secure` | 必须 `true` |
| `AICOSTOPS_DEV_MAILBOX_PATH` | `aicostops.auth.dev-mailbox-path` | 禁止显式设置（文件邮箱仅限 dev） |
| `AICOSTOPS_IAM_DEV_INVITATION_MAILBOX_PATH` | `aicostops.iam.dev-invitation-mailbox-path` | 禁止显式设置 |
| `AICOSTOPS_STORAGE_ENDPOINT` | `aicostops.storage.endpoint` | 必须带 host 的 absolute HTTP(S)；HTTPS + 非本地，或 HTTP + 内网且显式开启下一项；loopback/any-local/公网 HTTP 一律拒绝 |
| `AICOSTOPS_STORAGE_ALLOW_PLAINTEXT_INTERNAL` | `aicostops.storage.allow-plaintext-internal` | 默认 `false`；仅内网明文 HTTP 对象存储（如 compose 内 MinIO）显式 `true` |
| `AICOSTOPS_STORAGE_ACCESS_KEY` | `aicostops.storage.access-key` | 非空且非仓库示例值 |
| `AICOSTOPS_STORAGE_SECRET_KEY` | `aicostops.storage.secret-key` | 非空且非仓库示例值 |
| `AICOSTOPS_ALLOWED_ORIGINS` | `aicostops.auth.allowed-origins` | 逗号分隔、逐项解析；拒绝空项/非 HTTP(S)/loopback；域名含 `localhost` 字样不误杀 |
| `SPRING_DATASOURCE_PASSWORD` | `spring.datasource.password` | 禁止本地默认 `change-me-local-only` |

错误消息只包含环境变量名与原因，从不打印配置值。

### 2.1 相关运行时安全开关（非 fail-fast 校验器）

| 环境变量 | 默认 | 说明 |
|---|---|---|
| `AICOSTOPS_GATEWAY_PROVIDER_ALLOW_PRIVATE_ENDPOINTS` | `false` | Gateway 默认对 Provider 目标做公网单播解析并启用内置 OpenAI/MiMo 主机白名单；仅本地 mock / 内网 Provider 显式 `true` |
| `AICOSTOPS_GATEWAY_ADVISOR_WORKER_ENABLED` | `true` | Advisor 推理 worker 开关；关闭后不再 claim 任务 |
| `AICOSTOPS_GATEWAY_ADVISOR_WORKER_BATCH_LIMIT` | `10` | 单次调度 tick 最多处理的 Advisor 任务数 |
| `AICOSTOPS_RECONCILIATION_STALE_RUN_RECOVERY_ENABLED` | `true` | 对账僵尸 `RUNNING` run 回收开关 |
| `AICOSTOPS_RECONCILIATION_STALE_RUN_TIMEOUT` | `30m` | 超过该时长的 `RUNNING` run 视为僵尸 |
| `AICOSTOPS_RECONCILIATION_STALE_RUN_SWEEP_INTERVAL` | `5m` | 回收扫描间隔 |
| `AICOSTOPS_RECONCILIATION_STALE_RUN_SWEEP_INITIAL_DELAY` | `30s` | 启动后首次扫描延迟 |
| `AICOSTOPS_RECONCILIATION_STALE_RUN_SWEEP_BATCH_SIZE` | `100` | 单轮最多收敛条数 |

## 3. TLS 边界

```text
Internet/client
→ HTTPS TLS terminator / ingress（HSTS）
→ Frontend/Control Plane 私有 HTTP hop（受信任转发头）
→ MySQL / Redis / S3
```

- Backend 不直接暴露公网。
- `server.forward-headers-strategy: native` 配合 `tomcat.remoteip.internal-proxies`
  只信任 loopback / RFC1918 来源。
- 证书、私钥永不入库；由部署平台/密管提供。

## 4. 本地验证

```powershell
# 单元测试（不依赖容器）
Set-Location "E:\AI-CostOps\backend"
.\mvnw.cmd -B "-Dtest=ProductionConfigurationValidatorTest" test

# 正常本地开发启动不受影响：validator 仅在 prod profile 注册
.\scripts\dev\start-infra.ps1
.\mvnw.cmd -B spring-boot:run -Dspring-boot.run.profiles=local
```

## 5. 应用方式

```powershell
# 生产容器/进程
SPRING_PROFILES_ACTIVE=prod AICOSTOPS_JWT_SIGNING_KEY=... SPRING_DATASOURCE_PASSWORD=... `
AICOSTOPS_STORAGE_ENDPOINT=https://s3.example.internal AICOSTOPS_STORAGE_ACCESS_KEY=... `
AICOSTOPS_STORAGE_SECRET_KEY=... AICOSTOPS_STORAGE_BUCKET=... `
AICOSTOPS_ALLOWED_ORIGINS=https://costops.example.com java -jar backend.jar
```

任一必需变量缺失或为 dev 默认值时进程启动即失败（退出非零），不会进入 READY。

## 6. 证据

- 测试 `backend/src/test/java/com/aicostops/config/ProductionConfigurationValidatorTest.java`
  （32 用例）：blank/short/示例前缀 JWT、dev bootstrap、public registration、insecure cookie、
  file mailbox、loopback/any-local/十进制 IP/IPv4-mapped IPv6 存储 endpoint、公网 HTTP、
  内网明文 opt-in、示例存储凭据、weak DB password、逐项 allowed-origins（含
  `localhost.example.com` 不误杀）、显式安全值通过。
- 完整后端回归在 AIC-083 最终验收重新执行。