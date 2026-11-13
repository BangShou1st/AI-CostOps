# 详细设计索引

本目录按版本与维护批次保存可执行的详细设计。V1/V2 设计是已交付的历史基线；
维护期设计用于记录真实缺陷或必要改造的边界、取舍与验证方式。

## 版本设计

| 目录 | 范围 | 状态 |
|---|---|---|
| [`v1/`](v1/README.md) | V1 控制面、财务核心、导入、权限与前端 IA | FROZEN / V1 DELIVERED |
| [`v2/`](v2/README.md) | V2 Gateway、预算预占、流式计量、结算与路由 | FROZEN / V2 RELEASED |

## 维护期设计

| 日期 | 设计 | 主题 |
|---|---|---|
| 2026-10-10 | [成本分析模块职责整理设计](maintenance/2026-10-10-cost-intelligence-refactor-design.md) | Cost Intelligence 服务拆分与事务边界 |
| 2026-10-10 | [目录结构审查](maintenance/2026-10-10-directory-structure-review.md) | 02/03 文档与源码目录职责 |
| 2026-10-11 | [预测历史证据](maintenance/2026-10-11-forecast-history-evidence.md) | 预测可观测历史与拒绝窗口 |
| 2026-10-12 | [成本历史覆盖](maintenance/2026-10-12-cost-history-coverage.md) | 历史覆盖与 revision |
| 2026-10-13 | [账单链路入口审查](maintenance/2026-10-13-billing-chain-entry-review.md) | 外部账单归集入口 |
| 2026-10-13 | [报销链路入口审查](maintenance/2026-10-13-expense-reimbursement-chain-entry-review.md) | 报销与审批入口 |
| 2026-10-13 | [预测资格与负净额](maintenance/2026-10-13-forecast-eligibility-negative-net.md) | 预测资格判定 |
| 2026-11-10 | [后端核心链路与并发可靠性改造设计](maintenance/2026-11-10-backend-reliability-refactor-design.md) | CO-00 ~ CO-05 工作包与 F-01 ~ F-09 不变量 |

## 阅读顺序

```text
当前状态与边界 → PROJECT_CONTEXT.md / AGENTS.md
V1 基础设计   → v1/README.md
V2 网关设计   → v2/README.md
维护期改造    → 对应 maintenance 设计 + 03-acceptance 验收记录
```
