# 10. 系统架构 — V0.2

## 1. V1 总体架构

选择：

> **Spring Boot Modular Monolith + MySQL + Redis + S3-compatible Object Storage + React**

部署：

> **Docker Compose integrated stack**

不是微服务。

```text
Browser
  |
  v
Nginx / React
  |
  | /api
  v
Spring Boot
  |        |        |
  v        v        v
MySQL    Redis    MinIO/S3
```

## 2. 技术基线

### 后端

```text
Java 21
Spring Boot 4.1
Spring MVC
Spring Security
Spring Validation
Plain MyBatis（Spring Boot Starter 4.x；MyBatis Core 3.5.x）
Flyway
Micrometer / Actuator
```

### 数据与基础设施

```text
MySQL 8.4 LTS / InnoDB
Redis
S3-compatible Object Storage
MinIO for local/dev
```

### 前端

```text
React 19
TypeScript
Vite
React Router
TanStack Query
Ant Design
ECharts
```

### 测试与交付

```text
JUnit 5
AssertJ
Testcontainers
ArchUnit
Vitest
React Testing Library

Maven
Docker
Docker Compose
Nginx
GitHub Actions
```

## 3. MySQL 8.4 LTS

项目核心数据库需求：

```text
ACID transaction
unique constraints
foreign keys
row-level locking
atomic conditional update
batch write
index/query tuning
JSON metadata
```

V1 不依赖某个数据库厂商的专有扩展，因此将 MySQL 8.4 LTS 作为稳定数据库基线。

```text
MySQL = Source of Truth
```

负责：

- User / Organization / Permission；
- Evidence metadata；
- Normalized facts；
- Ledger；
- Budget；
- Reconciliation；
- Billing Period；
- Audit。

## 4. Plain MyBatis

核心业务需要显式控制：

```text
conditional UPDATE
SELECT ... FOR UPDATE
batch INSERT
aggregation
complex reconciliation query
EXPLAIN / index
```

依赖方向：

```text
Domain Repository Interface
       ↓
Infrastructure MyBatis Mapper
       ↓
Explicit SQL
       ↓
MySQL
```

不采用通用 `ServiceImpl + CRUD` 作为核心领域实现方式。

## 5. Money

MySQL：

```text
DECIMAL(20, 8)
```

Java：

```text
BigDecimal
```

正式金额禁止 `FLOAT/DOUBLE`。

未实现 FX 时，禁止跨币种直接求和。

## 6. ID 策略

V1 uses MySQL monotonic internal keys:

```text
BIGINT AUTO_INCREMENT
```

This avoids adding Snowflake/TSID node/time infrastructure before a distributed ID requirement exists.

Business references may still have readable codes such as `IMP-*`, `REC-*`, `ADJ-*`.

API JSON serializes BIGINT IDs as strings so React/JavaScript never loses integer precision.

## 7. Raw Provider Metadata 策略

核心查询字段使用普通列。

不可预测/低频扩展：

```text
raw_metadata JSON
```

JSON 服务 schema evolution，不替代关系模型。

## 8. Redis 在 V1 的职责

### Refresh Session

```text
auth:refresh:{sessionId}
```

### Verification / Password Reset

```text
auth:verify:{purpose}:{target}
auth:reset:{token}
```

### Login / Security Rate Limit

```text
ratelimit:login:{ip}
ratelimit:login:{account}
```

### Permission Context Cache

```text
iam:user-context:{userId}
```

短 TTL + eviction/version。

### Dashboard Cache

```text
dashboard:project:{id}:{period}
dashboard:cost-center:{id}:{period}
```

短 TTL。

### Boundary

```text
MySQL = truth
Redis = runtime acceleration
```

Redis 不负责：

- Ledger 幂等最终保证；
- Budget 最终一致性；
- Billing Period 正式状态。

## 9. Evidence 存储

MySQL 保存 metadata/object key。

MinIO/S3 保存 ZIP/CSV/XLSX/PDF。

通过：

```text
ObjectStoragePort
```

抽象，不让 Domain 依赖 MinIO SDK。

## 10. Import Pipeline

V1：

> **DB-backed Job + Spring TaskExecutor**

```text
API upload
→ Evidence + ImportJob transaction
→ commit
→ worker claims job
```

MySQL job claiming 可使用：

```text
SELECT ...
FOR UPDATE SKIP LOCKED
LIMIT N
```

或实现时验证后的等价可靠方案。

V1 不强制 RabbitMQ。

## 11. 幂等设计

四层：

```text
file sha256
business evidence identity
record fingerprint
posting_key UNIQUE
```

Redis `SETNX` 只可优化短期窗口，不能替代 MySQL unique constraint。

## 12. 事务边界

Confirm/Post 关键路径：

```text
allocation confirmed
+
ledger entries inserted
+
posting source marked
+
budget/commitment transition
+
audit/outbox persisted
```

V1.5 若引入消息：

```text
DB transaction
→ Transactional Outbox
→ Publisher
→ RabbitMQ
```

## 13. Ledger 持久化

Database：

- stable entry id；
- posting_key unique；
- FK/CHECK where useful；
- DECIMAL；
- append correction；
- 不提供通用 update SQL。

Repository：

```text
post()
appendCorrection()
```

## 14. Budget 并发控制

V1 优先 MySQL 原子条件更新：

在确认相关 BillingPeriod 仍为 `OPEN` 后，使用原子条件更新：

```sql
UPDATE budget
SET committed_amount = committed_amount + :amount,
    version = version + 1
WHERE id = :id
  AND status = 'ACTIVE'
  AND total_amount - actual_amount - committed_amount >= :amount;
```

affected rows = 0 表示预算不足、Budget 不可用，或并发事务已消费可用额度。

对于已经发生的 Provider cost，Ledger posting 更新 `actual_amount` 时不以剩余额度为前置条件；超预算要被记录，而不是隐藏真实成本。

V2 高频 Gateway Reservation 才评估 Redis + Lua。

## 15. IAM / Security

详见 `13-iam-auth-design.md`。

核心：

```text
Access JWT = short-lived
Refresh Session = Redis
User/Role/Permission/Data Scope = MySQL truth
```

## 16. 前端架构

```text
React 19 + TypeScript + Vite
```

Server State：

```text
TanStack Query
```

Client State：

```text
React state/context
```

只有明确需求再引入 Zustand/Redux。

UI：

```text
Ant Design
```

主要页面：

```text
/login
/register
/forgot-password
/invite/:token
/workbench
/evidence
/imports
/costs/*
/expenses
/ledger
/reconciliation
/billing-periods
/budgets
/settings/*
```

Ledger Detail 必须能展示完整 lineage。

## 17. Cache 策略

```text
cache-aside
short TTL
explicit invalidation where useful
```

适合：

- permission context；
- dashboard aggregates；
- lookup dictionaries。

不缓存为权威：

- ledger posting；
- budget truth；
- period close state。

## 18. 可观测性

Metrics：

```text
auth_login_success_total
auth_login_failed_total
redis_operation_failures
import_jobs_total
import_failed_total
import_duration
unallocated_charges
ledger_posted_total
budget_commit_conflicts
reconciliation_open
period_close_duration
```

Logs 不记录 password/refresh token/raw API key/Prompt/Response。

## 19. V1.5

按真实需求加入：

```text
Transactional Outbox
RabbitMQ
Prometheus
Grafana
bulk optimization
failure injection
```

## 20. V2 Gateway

新增：

```text
Spring WebFlux
Netty
Redis / Lua
Resilience4j
rate limit
timeout
circuit breaker
streaming
```

流程：

```text
Client
→ Auth/Internal Key
→ Attribution
→ Budget Reservation
→ Provider
→ Stream
→ Usage Capture
→ Settlement
→ MySQL Ledger
```

默认不保存 Prompt/Response 正文。

## 21. 架构 Definition of Done

- 模块边界在代码中可见；
- MySQL 是账务 truth；
- Redis 每个 key 有生命周期/owner；
- forbidden dependency 有 ArchUnit；
- DB constraints 与 domain invariant 对齐；
- auth/session revoke 有自动测试；
- Redis unavailable 有故障测试；
- provider parser 有 fixture；
- `docker compose up -d` 可运行完整 V1；
- 所有性能数字可复现。


## 22. Git 协作

Single public repository, two contributors:

```text
Issue
→ short-lived branch
→ PR
→ CI
→ optional peer review
→ squash merge
→ main
```

`main` Ruleset:

- Active, Default branch, empty bypass;
- restrict deletion;
- linear history;
- PR required;
- required approvals = 0;
- peer review optional;
- conversation resolution not required;
- block force push;
- squash only.

Required status checks are added after workflows exist.

See `docs/architecture/15-git-collaboration.md`.


## 23. 仓库 / 源码架构

The implementation is a monorepo:

```text
AI-CostOps/
├── backend/        # Spring Boot modular monolith
├── frontend/       # React management application
├── docs/           # research / domain / architecture / detailed design
├── deploy/         # Nginx and later observability config
├── .github/        # Issues / PR / Actions / later CODEOWNERS
├── scripts/        # reviewed reusable scripts
├── compose.yaml
└── .env.example
```

Backend uses package-by-feature:

```text
com.aicostops.<module>/
  api/
  application/
  domain/
  infrastructure/
```

rather than a whole-system `controller/service/mapper/entity` layering.

Frontend uses feature ownership:

```text
src/features/<feature>/
```

with shared application infrastructure under `src/app` and `src/api`.

Generated output, local runtime data, IDE state, secrets and raw Provider account exports are not part of the source repository.

See Detailed Design 13–15.
