# 11. 版本路线图 — V0.2

## V0 — Research & Design

已完成：

- 5 Provider research；
- FOCUS/FinOps baseline；
- Provider mapping；
- Canonical Domain；
- Business Invariants；
- V1 scope；
- MySQL/Redis/React/Docker 技术方向；
- IAM/Auth design；
- Test strategy。

# V0.5 — V1 Detailed Design

Before coding:

```text
Module boundaries
Data model
State machines
Transactions / idempotency / concurrency
API contract
Permission matrix
Redis contracts
Provider import contracts
Frontend IA
Error/observability
Git governance
```

After human approval:

```text
Detailed Design
→ Implementation Plan 1.0 (`docs/implementation/`)
→ GitHub Milestones / Issues
→ Coding
```

# V1 — AI Spend Ledger

## 目标

> **把已经花出去的 AI 钱算明白，并提供完整可用的企业后台身份/权限闭环。**

### 技术栈

```text
Java 21
Spring Boot 4.1
Spring Security
Plain MyBatis 4
MySQL 8.4 LTS
Redis
Flyway
MinIO/S3
React 19
TypeScript
Ant Design
TanStack Query
Docker Compose
Nginx
```

### 业务范围

```text
IAM
Evidence Import
Raw Record
Normalize
Allocation
Expense/Approval
Budget Commitment
Ledger
Reconciliation
Period Close
Audit
```

### Redis 在 V1 的职责

```text
Refresh Session
Verification/Reset TTL
Login Rate Limit
Permission Cache
Dashboard Cache
```

### Definition of Done

- auth refresh/logout/revoke；
- role/data scope；
- duplicate import no duplicate posting；
- crash/retry；
- MySQL budget concurrency correctness；
- posted ledger immutable；
- correction traceable；
- reconciliation cases；
- closed period guard；
- Redis loss does not corrupt financial truth；
- full Docker Compose smoke；
- CI green。

# V1.5 — 稳定性与工程强化

按测量结果加入：

```text
Transactional Outbox
RabbitMQ
Prometheus
Grafana
batch tuning
index tuning
failure injection
```

Benchmark：

```text
100k
500k
1m facts
```

只报告实测数字。

# V2 — 实时 AI Gateway

## 目标

> **开始控制钱是怎么花出去的。**

Stack extension：

```text
Spring WebFlux
Netty
Redis + Lua
Resilience4j
```

Scope：

```text
OpenAI-compatible API
Streaming Proxy
Internal Credentials
Identity/Attribution
Budget Reservation
Provider Routing
Realtime Metering
Settlement
Existing MySQL Ledger
```

Redis V2：

```text
rate limit
short idempotency window
budget reservation
request state
reserve → settle → release
```

最终财务 truth：

```text
MySQL Ledger
```

# V3 — 混合治理

```text
Realtime API ------\
                    > MySQL Ledger
Statements --------/
```

Budget：

```text
Total
Committed
Reserved
Actual
Available
```

# V4 — 工程单位经济性（可选）

候选：

```text
Cost / successful agent task
Cost / PR
Cost / issue
Cost / eval pass
```

只有业务 outcome 定义可靠后再做。

# 技术栈守门规则

任何新增技术先问：

1. 解决什么明确问题？
2. 现有 MySQL/Redis/模块不能合理解决吗？
3. 能否被 benchmark/test 证明？
4. 两人团队维护成本是否合理？

默认不加入：

```text
Kafka
Elasticsearch
Kubernetes
service mesh
microservices
XXL-JOB
```

除非后续真实需求推动。
