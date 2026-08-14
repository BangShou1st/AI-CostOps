# 15. 配置与环境设计

## 1. 目标

同一份源码可以运行在：

```text
Local IDE
Docker Compose
CI
```

而不提交 Secret、不创建每个人自己的配置 Branch。

V1 不假装已经设计完整生产 Secret Platform。

## 2. 配置优先级

Spring Boot：

```text
application.yml
→ 安全默认值 / 结构

Environment Variables
→ 环境相关值 / Secret
```

禁止提交：

```text
application-secret.yml
application-张三.yml
```

## 3. `application.yml`

可以 Commit：

```text
Server Setting
Actuator Policy
Upload Limit
Parser Setting
Non-secret Timeout
Feature Default
```

外部化：

```text
DB URL / Username / Password
Redis Host / Password
S3 Endpoint / Access / Secret
JWT Signing Key
Mail Credential
```

## 4. Environment Variable 命名

优先：

```text
SPRING_DATASOURCE_URL
SPRING_DATASOURCE_USERNAME
SPRING_DATASOURCE_PASSWORD

AICOSTOPS_REDIS_HOST
AICOSTOPS_REDIS_PORT
AICOSTOPS_REDIS_PASSWORD

AICOSTOPS_STORAGE_ENDPOINT
AICOSTOPS_STORAGE_BUCKET
AICOSTOPS_STORAGE_ACCESS_KEY
AICOSTOPS_STORAGE_SECRET_KEY

AICOSTOPS_JWT_SIGNING_KEY
AICOSTOPS_ALLOW_PUBLIC_REGISTRATION
```

Spring 已有标准变量就用标准；自定义统一 `AICOSTOPS_*`。

## 5. `.env.example`

Commit：

```text
.env.example
```

示例：

```dotenv
MYSQL_DATABASE=aicostops
MYSQL_USER=aicostops
MYSQL_PASSWORD=change-me-local-only

REDIS_PASSWORD=change-me-local-only

MINIO_ROOT_USER=aicostops
MINIO_ROOT_PASSWORD=change-me-local-only

AICOSTOPS_JWT_SIGNING_KEY=replace-with-local-development-key
AICOSTOPS_ALLOW_PUBLIC_REGISTRATION=true
```

这些只是 Local Example，不是 Production Credential。

真实 `.env` Ignore。

## 5.1 M2 Evidence / Import 配置

### Storage（Evidence 对象存储）

```text
AICOSTOPS_STORAGE_ENDPOINT        http://localhost:9000
AICOSTOPS_STORAGE_ACCESS_KEY      aicostops
AICOSTOPS_STORAGE_SECRET_KEY      change-me-local-only
AICOSTOPS_STORAGE_BUCKET          aicostops-evidence
AICOSTOPS_STORAGE_UPLOAD_LIMIT    512MB   # Spring DataSize；MB = 1024^2 字节（二进制 MiB）
AICOSTOPS_STORAGE_AUTO_CREATE_BUCKET true
```

Multipart 框架上限略高于领域上限：

```text
AICOSTOPS_MULTIPART_MAX_FILE_SIZE    520MB
AICOSTOPS_MULTIPART_MAX_REQUEST_SIZE 525MB
```

### Ingestion Worker

```text
AICOSTOPS_IMPORT_WORKER_ENABLED             true
AICOSTOPS_IMPORT_WORKER_CONCURRENCY         2
AICOSTOPS_IMPORT_POLL_INTERVAL              1s
AICOSTOPS_IMPORT_LEASE_DURATION             60s
AICOSTOPS_IMPORT_HEARTBEAT_INTERVAL         20s
AICOSTOPS_IMPORT_MAX_LEASE_RECOVERIES       3
AICOSTOPS_IMPORT_PERSISTENCE_BATCH_SIZE     500
```

测试环境默认 `aicostops.ingestion.worker-enabled=false`（`application-test-defaults.yml`），
只有 worker/coordinator 专项测试显式开启，避免普通集成测试出现异步竞态。

### MinIO 初始化

Bucket 只在首次真实存储操作时懒初始化（bucketExists / makeBucket 后 memoize）。
Bean 构造、`@PostConstruct`、普通 Spring context 启动不得访问 MinIO，否则会破坏
不依赖对象存储的 M1 测试。MinIO 镜像固定：
`quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z`（该 release tag 在 quay.io 存在；
plan 原指定的 `RELEASE.2025-10-15T17-29-55Z` 在官方 registry 不存在）。

## 6. Local IDE Mode

推荐：

```text
MySQL / Redis / MinIO
→ Docker

Backend
→ IDEA / ./mvnw spring-boot:run

Frontend
→ npm run dev
```

需要开放本地端口时使用 `compose.dev.yaml`，不要每个人都改 tracked `compose.yaml`。

## 7. Full Compose

```bash
docker compose up -d
```

启动：

```text
frontend/nginx
backend
mysql
redis
minio
```

Browser 不直接访问 MySQL/Redis。

## 8. CI

GitHub Actions 使用：

```text
Ephemeral Service / Testcontainers
Generated Test Credential
GitHub Secret（确实需要时）
```

CI 不依赖开发者 `.env`。

## 9. Frontend Environment

重要规则：

> 编译到浏览器里的变量都是公开信息。

所以禁止：

```text
VITE_DB_PASSWORD
VITE_JWT_SIGNING_KEY
VITE_PROVIDER_API_KEY
VITE_MINIO_SECRET
```

API Base 默认：

```text
/api/v1
```

避免写死某个人电脑地址。

## 10. Secret Leak

如果 Secret 怀疑泄漏：

```text
Rotate / Revoke
→ 必要时清 Git History
```

仅新增 `.gitignore` 不算修复。

## 11. Demo / Seed Data

Demo Data 必须：

```text
Synthetic
可重复
明确标注
```

Roles/Permissions 属于 Reference Seed，可以正常 Flyway。

Demo Financial Data 不应该混入 Production Migration。

## 12. Time Zone

Instant 持久化 UTC。

Frontend 按用户 Locale 展示。

BillingPeriod 使用 Date：

```text
[start_date,end_date)
```

避免浏览器时区把月账期边界改掉。

## 13. Logging Profile

Local 可以 Human-readable Console。

CI / Production-like 可以 Structured Log。

任何 Profile 都不能打印 Secret / Raw Provider Payload。

## 14. Feature Config

V1 只需要简单 Config，例如：

```text
ALLOW_PUBLIC_REGISTRATION
```

不建设 Feature Flag Platform。

影响财务解释的 Provider Parser Version / Rule Version 应进入可审计领域数据或代码，而不是隐藏 Runtime Toggle。

## 15. 新配置变量 PR 规则

新增 Environment Variable 时，PR 必须说明：

```text
Name
Purpose
Safe Default?
Secret?
Compose Required?
CI Required?
```

需要时同步 `.env.example`。
