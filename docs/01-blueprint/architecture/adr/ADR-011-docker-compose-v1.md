# ADR-011 — V1 使用 Docker Compose 完整集成交付

**状态：** Accepted
**日期：** 2026-08-12

## 决策

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

V1.5 profiles：

```text
rabbitmq
prometheus
grafana
```

## 影响

本地/CI/演示环境更一致，必须认真设计 healthcheck、volume、migration。

## 不做的内容

V1 不上 Kubernetes。
