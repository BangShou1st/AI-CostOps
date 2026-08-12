# ADR-008 — V1 采用 MySQL 8.4 LTS + Plain MyBatis

**状态：** Accepted
**日期：** 2026-08-12

## 背景

V1 的主要工程难点：

```text
financial transaction
idempotency
budget concurrency
batch import
reconciliation query
index tuning
```

## 决策

```text
MySQL 8.4 LTS
InnoDB
Plain MyBatis 4
Flyway
```

不以 PostgreSQL/JPA 作为本项目主线。

## 为什么这样选

- 领域不依赖特定数据库扩展；
- MySQL 适合本次技术栈差异化；
- Plain MyBatis 让 SQL/锁/批量/EXPLAIN 可见；
- MySQL 8.4 LTS 提供稳定版本基线。

## 约束边界

Domain 不依赖 MyBatis；Mapper 属于 infrastructure。
