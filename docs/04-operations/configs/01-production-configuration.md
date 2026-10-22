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
| `AICOSTOPS_JWT_SIGNING_KEY` | `aicostops.auth.jwt-signing-secret` | 非空且 ≥32 字符 |
| `AICOSTOPS_DEV_BOOTSTRAP_ENABLED` | `aicostops.auth.dev-bootstrap-enabled` | 必须 `false` |
| `AICOSTOPS_ALLOW_PUBLIC_REGISTRATION` | `aicostops.auth.allow-public-registration` | 必须 `false`（除非显式评审允许策略） |
| `AICOSTOPS_REFRESH_COOKIE_SECURE` | `aicostops.auth.refresh-cookie-secure` | 必须 `true` |
| `AICOSTOPS_DEV_MAILBOX_PATH` | `aicostops.auth.dev-mailbox-path` | 禁止显式设置（文件邮箱仅限 dev） |
| `AICOSTOPS_IAM_DEV_INVITATION_MAILBOX_PATH` | `aicostops.iam.dev-invitation-mailbox-path` | 禁止显式设置 |
| `AICOSTOPS_STORAGE_ENDPOINT` | `aicostops.storage.endpoint` | HTTPS、非 loopback |
| `AICOSTOPS_STORAGE_ACCESS_KEY` | `aicostops.storage.access-key` | 非空 |
| `AICOSTOPS_STORAGE_SECRET_KEY` | `aicostops.storage.secret-key` | 非空 |
| `AICOSTOPS_ALLOWED_ORIGINS` | `aicostops.auth.allowed-origins` | 显式非 loopback 来源 |
| `SPRING_DATASOURCE_PASSWORD` | `spring.datasource.password` | 禁止本地默认 `change-me-local-only` |

错误消息只包含环境变量名与原因，从不打印配置值。

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
  （12 用例）：blank/short JWT、dev bootstrap、public registration、insecure cookie、
  file mailbox、localhost storage、blank storage secret、weak DB password、
  localhost origins、显式安全值通过。
- 完整后端回归在 AIC-083 最终验收重新执行。