# ADR-001 — V1 使用模块化单体

**状态：** Accepted for V1 design

## 背景

项目由两人开发，领域仍在收敛，同时 Ledger/Budget/Close 存在明显强事务边界；V1 使用 MySQL 作为业务 Source of Truth。

## 决策

V1 使用 Spring Boot Modular Monolith。

模块按业务域隔离，允许未来拆分，但当前单进程/单主库部署。

## 影响

优点：

- 事务简单；
- 本地开发和测试成本低；
- 两人能把业务做深；
- 避免分布式一致性喧宾夺主。

代价：

- 不能用“微服务”作为项目卖点；
- 需要依靠包边界/architecture test 防止模块耦合。

## Revisit

当出现独立扩缩容、团队 ownership、网关高吞吐等真实需求时再评估拆分。
