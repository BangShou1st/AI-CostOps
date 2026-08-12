# ADR-007 — Provider Adapter 作为 Anti-Corruption Layer

**状态：** Accepted

## 背景

五家 Provider 的字段、粒度、计费语义明显不同。

## 决策

任何 Provider-specific schema 只能存在于 adapter/integration 边界。

Core domain 使用 canonical concepts。

## 影响

新增 Provider 原则上不改 Ledger/Close core。

Adapter 只负责外部语义翻译，不负责企业内部 attribution/posting。
