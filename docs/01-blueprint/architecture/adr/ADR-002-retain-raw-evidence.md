# ADR-002 — 保留 Raw Evidence 与 Raw Provider Record

**状态：** Accepted

## 背景

Provider CSV/XLSX schema 会变化，Parser/Normalization 也可能有 bug。

## 决策

正式 Ledger Entry 必须能追溯到原始 Evidence；导入后不只保存 canonical fact。

## 影响

优点：

- 能重放；
- 能修 parser；
- 能审计；
- 不会因错误 normalization 丢失原始事实。

代价：

- 增加对象存储和数据生命周期管理；
- Evidence 可能包含敏感信息，需要权限/retention。

## 不做的内容

不保存与成本无关的 Prompt/Response 内容。
