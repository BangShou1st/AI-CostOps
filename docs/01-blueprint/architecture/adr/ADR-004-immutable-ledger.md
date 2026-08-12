# ADR-004 — POSTED Ledger 不做 destructive update

**状态：** Accepted

## 背景

财务型系统必须能解释历史数字为什么发生变化。

## 决策

POSTED Ledger Entry 不直接修改金额/归属；错误通过 Correction/Adjustment 追加。

## 影响

优点：

- 历史可审计；
- close 后修正有明确语义；
- reconciliation 和 current view 可重放。

代价：

- 查询 current balance 需要考虑 correction；
- UI 必须展示原始与调整关系。

## Exception

仅允许修复非账务语义的技术元数据时评估独立 maintenance path，不能绕过 Audit。
