# HC-04 热点预算和缓存验收

2026-11-26。本次验收覆盖 Gateway 预占、缓存、到期回收、Backend 结算与导入，以及分桶财务约束；WP6 已按 ABAB 交替压测、修复 `mock_provider.py` 与 S4 观测口径并补齐 S9 独立容器基准（502 全部归零、多预算/多组织呈正收益、全量结算 0 异常）。

- [WP6 同机 A/B 性能与根因复核报告](comparison.md)
- [WP6 逐轮聚合数据与构建摘要](results.json)
- [WP7 分桶正确性审查](bucket-gate.md)
- [HC-04 设计](../../../../02-development/designs/maintenance/2026-11-25-hc04-hot-budget-and-multilevel-cache-design.md)
