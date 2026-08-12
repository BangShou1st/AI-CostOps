# 12. 测试与验证计划 — V0.2

## 1. 目标

项目是否“不是 Demo”由可复现证据决定：

- 业务不变量自动验证；
- Auth/权限可信；
- Redis 使用有边界；
- 并发不破坏账；
- 失败可恢复；
- Docker 能复现实验环境。

## 2. 测试技术栈

Backend：

```text
JUnit 5
AssertJ
Testcontainers MySQL
Testcontainers Redis / Redis container
ArchUnit
Spring Boot integration test
```

Frontend：

```text
Vitest
React Testing Library
```

E2E 后续可选 Playwright。

## 3. Domain / DB 核心测试

### T-001 Duplicate Upload
重复 Evidence 不重复正式 Ledger。

### T-002 Same Business, Different Hash
重保存导致 SHA 变化，business duplicate candidate 仍可识别。

### T-003 Crash Mid Import
处理到 50% kill worker，retry 无重复 Raw/Fact/Post。

### T-004 Unknown Column
非关键新增列不导致整个 import 失败。

### T-005 Critical Column Missing
validation failure，不猜。

### T-006 Unallocated Charge
POST rejected。

### T-007 Budget Concurrency

```text
Available = 10,000
100 concurrent requests
each = 1,000
```

断言：

```text
success <= 10
committed <= 10,000
```

以 MySQL 结果为准。

### T-008 Ledger Immutability
POSTED 后不能 destructive update。

### T-009 Correction
旧 entry 保留，新 adjustment 追加。

### T-010 Reconciliation Difference
Internal 100 vs external 95 → Case OPEN。

### T-011 Close Blocker
material unresolved case → close rejected。

### T-012 Closed Period Write
rejected。

## 4. MySQL 专项验证

对核心查询保存 EXPLAIN：

- ledger by period/project；
- reconciliation；
- import job claim；
- duplicate detection；
- budget update。

验证：

- rollback 无半 posting；
- unique conflict 可转幂等结果；
- concurrent budget 不超分配；
- job claim 不重复；
- DECIMAL ↔ BigDecimal 无 float rounding。

## 5. IAM / Auth 测试

### A-001 Login Success
Access JWT + Redis Refresh Session + Audit。

### A-002 Login Failure Limit
Redis rate limit。

### A-003 Refresh Rotation
旧 token replay 失败。

### A-004 Concurrent Refresh
不能无限 fork session。

### A-005 Logout
Refresh Session revoke。

### A-006 Disabled Account
不能新登录，existing refresh revoke。

### A-007 Password Reset
old sessions invalid。

### A-008 Permission Scope
Employee/ProjectOwner/Finance data scope。

### A-009 Cache Eviction
Role/member 变化后旧权限不能长期继续授权。

## 6. Redis 边界测试

### R-001 Redis Flush/Restart

断言：

- Ledger 不变化；
- Budget 不变化；
- BillingPeriod 不变化；
- Dashboard 可重算；
- session 可按策略重新登录。

### R-002 Redis Unavailable During Financial Mutation

不能因为 cache/session store 失效造成错误账。

### R-003 Redis Unavailable During Login

安全控制不能无意 fail-open。

### R-004 Dashboard Cache

验证 cold/warm latency 和 stale 边界。

## 7. Provider 测试矩阵

| Provider | Header Fixture | Populated Synthetic | Unknown Column | Missing Column | E2E |
|---|---:|---:|---:|---:|---:|
| DeepSeek | Must | Must | Must | Must | Must |
| Kimi | Must | Must | Should | Must | Should |
| GLM | Must | Must | Should | Must | Should |
| MiMo | Must | Must | Must | Must | Must |
| OpenAI | Observed empty CSV + official JSON | Official JSON synthetic | Must | Must | Must |

## 8. 前端测试

关键页面：

```text
login
evidence import
unallocated review
expense approval
ledger lineage
reconciliation case
billing period close
budget
user/role management
```

测试重点：

- permission-based action；
- server error；
- mutation 后 TanStack Query invalidation；
- import 状态；
- close blocker；
- money/currency format。

## 9. Docker Compose Smoke

```text
docker compose up -d
```

验证：

1. MySQL healthy；
2. Redis healthy；
3. MinIO healthy；
4. Backend health；
5. Frontend 200；
6. Flyway head；
7. register/login；
8. upload synthetic fixture；
9. post ledger；
10. query ledger。

## 10. 性能验证

Synthetic：

```text
100k
500k
1m normalized facts
```

记录：

```text
import throughput
normalize throughput
MySQL batch write throughput
memory peak
ledger query P95
reconciliation duration
period close duration
dashboard cold/warm latency
Redis hit rate
```

设计目标：

- 500k import 不 OOM；
- 1m 下核心月度查询可用；
- 100 concurrent budget correctness = 100%；
- Redis cache 的收益由 benchmark 证明。

## 11. 故障注入

V1.5：

```text
MySQL transient failure
Redis restart
MinIO unavailable
worker crash
transaction timeout
duplicate job pickup
parser exception
RabbitMQ unavailable (if enabled)
```

## 12. 安全测试

- password/refresh/API key 不进日志；
- evidence 下载受权；
- CSRF/CORS 与 token transport 匹配；
- brute force / verification rate limit；
- role/data scope integration test。

## 13. 架构测试

```text
ledger domain
不得 import provider/deepseek
```

```text
provider adapter
不得调用 LedgerRepository.post
```

```text
domain
不得依赖 RedisTemplate / MyBatis Mapper
```

## 14. 完成证据

```text
test-report/
benchmark-report/
failure-injection-report/
schema-fixtures/
compose-smoke-report/
```

README 的数字必须能回溯报告。

## 15. 禁止虚构的指标

不说：

> “百万级高并发系统”

只因为表里有 1m 行。

不说：

> “Redis 提升 80%”

除非有 benchmark。


## 16. Git / PR 质量门禁

Before stable CI:

```text
PR required
required approvals = 0
peer review optional
squash merge
```

After stable workflow names exist, require:

```text
backend / unit
backend / integration
backend / architecture
frontend / lint
frontend / test
frontend / build
docker / build
```

## 17. OpenAI Fixture 真实性边界

The observed OpenAI CSV is an empty time-bucket export only.

Tests distinguish:

```text
ObservedEmptyCsvFixture
OfficialUsageApiJsonFixture
OfficialCostsApiJsonFixture
```

Populated synthetic OpenAI data is based on official API JSON contracts, not invented CSV headers.
