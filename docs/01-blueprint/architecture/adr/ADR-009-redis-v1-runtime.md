# ADR-009 — Redis 在 V1 正式使用，但不承担财务 Truth

**状态：** Accepted
**日期：** 2026-08-12

## 背景

V1 有天然 Redis 场景：

```text
Refresh Session
verification/reset TTL
login rate limiting
permission cache
dashboard cache
```

## 决策

V1 集成 Redis。

Redis owns：

```text
session
TTL data
rate limit
cache
```

MySQL owns：

```text
identity truth
ledger
budget
billing period
financial idempotency
```

## 影响

Redis 故障可影响 session/cache，但不能破坏账务正确性。
