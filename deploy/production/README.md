# AI CostOps — Production Deployment Boundary

> M9 / v1.1 Production Foundation。本文件只定义部署拓扑、TLS 边界与安全约束；
> 不包含任何真实 secret、证书或私钥，也不引入 Kubernetes/服务网格。

## 拓扑

```text
Internet / client
      │  HTTPS (TLS termination at ingress)
      ▼
HTTPS TLS terminator / ingress        ← 唯一公网暴露点；强制 HSTS
      │  trusted forwarded headers, private HTTP hop
      ▼
Frontend / Control Plane (backend)    ← 不在公网直接暴露
      │
      ▼
MySQL / Redis / S3-compatible storage
```

Backend 进程本身不终止 TLS。生产部署由反向代理 / Ingress 承担 TLS 终止，
并通过受信任的 sidecar/私有网络将 `X-Forwarded-*` 头传给后端
（`server.forward-headers-strategy: native` 仅信任 RFC1918/loopback 来源，见
`backend/src/main/resources/application.yml` 的 `tomcat.remoteip.internal-proxies`）。

## 强制要求（Deployment Requirements）

| 项 | 要求 |
|---|---|
| HTTPS | 对外一律 HTTPS；HTTP 到 HTTPS 跳转由 ingress 负责 |
| HSTS | Ingress 强制 `Strict-Transport-Security`（建议 `max-age=31536000; includeSubDomains`） |
| 转发头 | 仅信任明确的代理来源（RFC1918 / loopback），不可信任任意 `X-Forwarded-*` |
| Refresh cookie | `AICOSTOPS_REFRESH_COOKIE_SECURE=true`；`Secure` + `HttpOnly` + `SameSite` |
| 来源白名单 | `AICOSTOPS_ALLOWED_ORIGINS` 显式列出非 loopback 公网来源 |
| 后端暴露 | Backend/Control Plane 不直接暴露公网；仅反向代理可达 |
| 证书与私钥 | 永不提交仓库；由部署平台/密管提供 |
| 生产 profile | `SPRING_PROFILES_ACTIVE=prod`（激活 fail-fast 校验） |

## 必填环境变量（prod fail-fast）

以下变量缺失或为本地默认值时，`ProductionConfigurationValidator` 会在启动阶段拒绝：

- `AICOSTOPS_JWT_SIGNING_KEY` —— 强随机值，≥32 字符
- `SPRING_DATASOURCE_PASSWORD` —— 禁止 `change-me-local-only`
- `AICOSTOPS_STORAGE_ENDPOINT` —— HTTPS、非 loopback
- `AICOSTOPS_STORAGE_ACCESS_KEY` / `AICOSTOPS_STORAGE_SECRET_KEY` —— 显式提供
- `AICOSTOPS_STORAGE_BUCKET` —— 显式提供
- `AICOSTOPS_ALLOWED_ORIGINS` —— 显式非 loopback 来源

prod 下被禁止的 dev-only 配置（显式提供即拒绝启动）：

- `AICOSTOPS_DEV_BOOTSTRAP_ENABLED=true`
- `AICOSTOPS_ALLOW_PUBLIC_REGISTRATION=true`（无显式评审允许策略）
- `AICOSTOPS_REFRESH_COOKIE_SECURE=false`
- `AICOSTOPS_DEV_MAILBOX_PATH` / `AICOSTOPS_IAM_DEV_INVITATION_MAILBOX_PATH`（文件邮箱仅限 dev）

完整校验规则与本地验证命令见：

```text
docs/02-development/operations/01-production-configuration.md
```

## 非目标（M9 不引入）

- Kubernetes / service mesh / 多副本 HA 编排（符合部署目标时再设计）
- 应用内 TLS 终止
- 任何真实凭据/证书文件入库