# 14. Docker / Compose 部署设计

## 1. 目标

完整 V1：

```bash
docker compose up -d
```

即可启动，不要求手工安装 MySQL/Redis/MinIO。

## 2. 仓库结构

```text
ai-costops/
├── backend/
├── frontend/
├── docs/
├── deploy/
│   ├── nginx/
│   ├── mysql/
│   └── observability/
├── compose.yaml
├── compose.dev.yaml
└── .env.example
```

## 3. V1 服务

```text
frontend
backend
mysql
redis
minio
```

Frontend image：

```text
Node build stage
→ React dist
→ Nginx runtime
```

Nginx：

```text
/
→ React SPA

/api/
→ backend
```

## 4. Backend 镜像

Multi-stage：

```text
Maven/JDK builder
→ Spring Boot jar
→ JRE runtime
```

Backend 默认只在 Compose network 暴露。

## 5. MySQL

```text
MySQL 8.4 LTS
InnoDB
utf8mb4
```

Flyway 初始化/升级 schema。

Volume：

```text
mysql-data
```

## 6. Redis

Volume：

```text
redis-data
```

Redis 数据丢失允许出现：

- 重新登录；
- cache 重建。

不允许：

- Ledger 丢失；
- Budget 丢失；
- Close 状态丢失。

## 7. MinIO

Volume：

```text
minio-data
```

保存 Evidence。

代码抽象为 S3-compatible storage。

## 8. Health Check

至少：

```text
MySQL: mysqladmin ping
Redis: redis-cli ping
MinIO: health/client check
Backend: /actuator/health
```

Compose dependency 使用 health condition，不只依赖 container started。

## 9. Volumes

```text
mysql-data
redis-data
minio-data
```

```bash
docker compose down
```

不清数据。

```bash
docker compose down -v
```

显式删除开发数据。

## 10. 配置

`.env.example`：

```text
MYSQL_DATABASE=
MYSQL_USER=
MYSQL_PASSWORD=
JWT_SIGNING_KEY=
REDIS_PASSWORD=
MINIO_ACCESS_KEY=
MINIO_SECRET_KEY=
ALLOW_PUBLIC_REGISTRATION=
```

真实 secret 不 commit、不 bake image。

## 11. 网络

内部服务名：

```text
mysql
redis
minio
backend
frontend
```

浏览器不直接访问 MySQL/Redis。

开发端口覆盖放 `compose.dev.yaml`。

## 12. Compose Profile

V1.5：

```text
rabbitmq
prometheus
grafana
```

Profiles：

```text
messaging
observability
```

默认只启动核心。

## 13. Flyway

V1 单 backend 实例：

```text
backend start
→ Flyway migrate
→ application ready
```

多副本生产 migration 策略以后再评估。

## 14. Readiness / 降级策略

Backend readiness 明确：

- MySQL：核心 required；
- MinIO：Evidence 功能 required；
- Redis：认证/session/cache 的 degraded/failure policy 单独定义。

Redis 异常不能让财务数据库产生错误。

## 15. CI

GitHub Actions：

```text
backend:
  unit
  integration
  architecture
  build

frontend:
  lint
  test
  build

docker:
  image builds
  optional compose smoke
```

## 16. Smoke Test

Compose 启动后验证：

```text
frontend 200
backend health
flyway at head
mysql healthy
redis healthy
minio bucket
register/login
upload fixture
post sample ledger
query ledger
```

## 17. 不做的内容

V1 不做：

```text
Kubernetes
Helm
service mesh
multi-region
HA MySQL cluster
Redis Cluster
```
