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

`.env` 只保存本地配置并保持 ignored。

## 3. 推荐日常开发模式

基础设施：

```bash
docker compose -f compose.yaml -f compose.dev.yaml up -d mysql redis minio
```

后端：

```bash
cd backend
./mvnw spring-boot:run
```

前端：

```bash
cd frontend
npm ci
npm run dev
```

这样既有 IDE/HMR，又保持 MySQL/Redis/MinIO 环境一致。

## 4. 完整集成模式

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

最终 Profile/Goal 由 M0 CI 固定，但本地和 CI 使用同一套官方支持命令。

## 7. 前端命令

```bash
cd frontend
npm ci
npm run lint
npm test
npm run build
```

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
