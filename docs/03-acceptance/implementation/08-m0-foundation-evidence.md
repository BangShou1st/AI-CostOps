# 08. M0 Repository Foundation 实现证据

## 范围

本证据覆盖 AIC-002、AIC-003、AIC-004、AIC-005、AIC-006、AIC-007、AIC-008 与 AIC-010。AIC-009 不在本次代码实现范围内。

## 已实现基线

- Backend：Java 21、Spring Boot 4.1.0、Maven Wrapper、Spring Web/Validation/Security/Actuator、Flyway、MySQL Connector/J、MyBatis Spring Boot Starter 4.1.0（MyBatis Core 3.5.19）。
- Frontend：React 19.2.8、TypeScript 6.0.3、Vite 8.2.1、Router、TanStack Query、Axios、Vitest、ESLint。
- Infrastructure：MySQL 8.4、Redis 8.8.1、MinIO，均有持久卷、网络、环境变量和健康检查。
- Delivery：后端与前端多阶段 Dockerfile、Nginx SPA/API 代理、完整 Compose、7 个稳定命名的 GitHub Actions jobs。

## 2026-08-12 本地验证

| 验证 | 命令 | 结果 |
|---|---|---|
| Backend unit | `.\mvnw.cmd "-DexcludedGroups=architecture,integration" test` | 17 tests，0 failure |
| Backend architecture | `.\mvnw.cmd "-Dgroups=architecture" test` | 1 test，0 failure |
| Backend integration | `.\mvnw.cmd "-Dgroups=integration" verify` | Testcontainers MySQL 8.4；Flyway v1；1 test，0 failure |
| Security behavior | `.\mvnw.cmd -Dtest=M0SecurityConfigurationTest test` | 2 tests，0 failure；liveness 允许匿名访问，其余请求拒绝 |
| Frontend clean install | `npm ci` | 成功 |
| Frontend image | `docker build --tag ai-costops-frontend:local frontend` | 成功；镜像内执行 `npm ci` 与 `npm run build` |
| Backend image | `docker build --tag ai-costops-backend:local backend` | 成功；镜像内 Maven package 成功 |
| Compose config | `docker compose --env-file .env.example config --quiet` | 成功 |
| Full Compose | `docker compose --env-file .env.example up -d --build` | 成功 |
| Compose health | `docker compose --env-file .env.example ps` | backend、frontend、mysql、redis、minio healthy |
| HTTP smoke | `GET http://localhost:8080/` | 200 |
| Proxy/security smoke | `GET http://localhost:8080/api/v1/not-implemented` | 403 |

最终交付前还必须重新执行完整 Backend、Frontend 与 Compose 验证；本表应以最终报告中的最新结果为准。

## CI check 名称

```text
backend-unit
backend-integration
backend-architecture
frontend-lint
frontend-test
frontend-build
docker-build
```

这些名称为 AIC-009 的后续输入。只有真实 PR workflow 成功运行后，才能配置 required status checks。

## 已知限制

- M0 不包含 Login、用户/组织、Provider、归集、预算、Ledger、对账、关账或报表业务。
- Redis 与 MinIO 在 M0 仅完成运行环境和配置接线，尚无业务客户端行为。
- MinIO 使用可获得的固定社区镜像，仅用于本地开发；生产存储选型与安全加固不在本次范围。
- MyBatis 当前无 Mapper，因此启动日志中的 “No MyBatis mapper was found” 是 M0 预期状态。
- AIC-009 尚未执行；双人 clean-clone 验证仍需由第二位 Contributor 独立完成。
