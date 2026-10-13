# 预测资格规则与负净额处理（RUN_VERSION 4）

日期：2026-10-13（Asia/Shanghai）。性质：已实施并验证的规则说明。

## 问题（U1）

`ForecastEngine` 的 RECENT_RUN_RATE 路径用最近窗口净额平均后乘剩余天数，没有符号限制；
连续 3 天实际净额各为 -1 CNY、剩余 20 天时会生成 -20 CNY 的预测。带符号金额被持久化到
`cost_forecast_snapshot` 后，`BudgetRiskService.assess` 拒绝任何负组成金额，导致：

- `GET /cost-intelligence/budget-risks` 返回 500（IllegalArgumentException）；
- Advisor 的 `requestExplanation`（BUDGET_RISK subject）在同一事务内装配证据失败，
  整个请求回滚。

DAMPED_HOLT 路径则把单步估计截为非负，与兜底路径行为不一致。

消费链失败已在 `NegativeLedgerConsumptionChainIntegrationTest` 用真实 MySQL 复现
（修复前该测试失败于断言/异常；修复后通过）。

## 规则选择

账本负金额是真实的财务事实（追加式冲正、替换、退费），一律保留，不改口径、不截零。
需要区分的是"账本事实"与"未来消费估计"：

1. **带符号账本事实**：`ledger_entry.amount` 保持原样；成本序列（orgDaily 等）继续按
   POSTED 有效血统聚合带符号金额。
2. **未来消费估计的资格**：预测回答"按近期水平，剩余期间还会花多少"。资格检查作用于
   **实际外推窗口**：DAMPED_HOLT 使用完整连续观测后缀，RECENT_RUN_RATE 只外推最近
   7 天，因此两个路径各自要求其计算窗口的净额 > 0（净额 ≤ 0 且含负观测即冲正主导，
   返回**预测不可用**，不落 forecast 快照；预算风险与 Advisor 报告
   `forecastAvailable=false`）。只检查完整后缀会漏掉"前段净额为正掩盖近期冲正主导
   尾部"的组合（6 天 +10 + 7 天 -1：整体净 +53，尾部净 -7，外推 -20）。
3. **全零观测**：合法的零预测——全零窗口均值与总和都为 0，样本方向一致且无反向证据，
   零是**有证据的零**，不是截零；与冲正主导不可用明确区分。

按样例固定（`ForecastHistoryEvidenceTest`）：

| 连续观测净额形态 | 资格 | 结果 |
| --- | --- | --- |
| 每日 +2.00（3–29 天） | 可用 | 原有算法与样本等级不变 |
| 每日 -1.00（3–21 天，冲正主导） | 不可用 | 不落快照，forecastAvailable=false |
| 正负混合、计算窗口净额 > 0 | 可用 | 净额口径照常外推 |
| 正负混合、计算窗口净额 ≤ 0（含负观测） | 不可用 | 不落快照；含"整体净 +53 但最近 7 天净 -7"的双窗口组合 |
| 全零观测（真实零/复核零） | 可用 | 预测 0：有证据的零，不是截零 |
| 未知日期打断后缀 | 按后缀重算 | 不足 3 天拒绝 |

"全零观测预测 0"与"冲正主导不可用"的区别依据：全零的窗口均值与总和都为 0，样本方向
一致且无反向证据；冲正主导的窗口净额 ≤ 0 且含负观测，意味着近期水平本身非正，外推为负
没有业务含义，外推为正没有证据。两者都不会产出负金额。

## 实现

- `ForecastEngine.forecast`：对每个候选计算窗口应用资格检查——完整连续观测后缀
  （DAMPED_HOLT 路径）与最近 7 天窗口（RECENT_RUN_RATE 路径）各自要求净额 > 0；
  净额 < 0 或（净额 == 0 且存在负金额观测）时抛 `ForecastUnavailableException`
  （领域信号，区别于 IllegalArgumentException 的输入错误）；全零窗口返回预测 0。
  Holt 路径保留非负截断作为数值保险（平滑可能产生极小负数）。
- `CostIntelligenceAnalysisService.persistForecast`：捕获
  `ForecastUnavailableException`，不写快照行（保持"缺席即不可用"的既有契约）。
- `BudgetRiskService`：`actual` 是带符号账本净额（credits/reversals 可合法使其为负，
  V11 schema 对 actual_amount 有意不设非负 CHECK），敞口直接按带符号净额累计；
  承诺、预占、未来消费与预算总额是纯敞口分量，仍拒绝负值。

## 版本升级（RUN_VERSION 3 → 4 → 5）

输入指纹的 canonical 串包含算法版本，唯一键为
`(org_id, analysis_date, currency, run_version, input_fingerprint)`：

- v3→v4：引入冲正主导不可用规则（首次修复）。
- v4→v5：资格检查对齐实际外推窗口（尾部 7 天单独校验），预算风险接受带符号负 actual。
  两次升级后当前查询（summary/anomalies/forecasts/recommendations/budget-risk/Advisor
  证据）只消费 version=5 且指纹匹配的 run；旧版本快照自然退出，不删除、不改写；
- 已 COMPLETED 的旧版本任务保持原样（历史声明与审计完整）；同输入的新版本分析生成新 run；
- 无数据库迁移：唯一键已覆盖新版本行，`input_fingerprint` 默认值行为不变。

## 页面口径

- 预测页与总览把 `projectedAmount` 表述为**剩余期间预计费用**（它只是剩余天数的估计）；
  预算风险的 `projectedPeriodEnd` 保留**预计周期末**（actual + committed + forecast）。
- LOW/MEDIUM/HIGH 明确标注为**历史样本等级**（连续有效观测天数门槛），尚未经过
  回测准确率校准，不代表预测误差保证。
