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
2. **未来消费估计的资格**：预测回答"按近期水平，剩余期间还会花多少"。只有当连续有效
   观测后缀的日均净额 > 0 时，外推才有可解释的业务含义。日均净额 ≤ 0 的历史
   （冲正主导、纯退款、净额为零）不足以支撑任何方向的外推，返回**预测不可用**
   （不落 forecast 快照；预算风险与 Advisor 报告 `forecastAvailable=false`）。
   这沿用了既有的"无足够有效历史 → forecastAvailable=false"语义，不新增第二套口径。
3. **不区分不可用与零费用的证据**：未来费用为零的合法场景（连续观测净额恰好为 0 且
   由此判断无未来费用）同样不生成预测——预测缺席本身就是"不可用"信号，前端与 API
   均以 `forecastAvailable=false` / 无快照行表达，绝不会渲染一个截零金额去宣称
   "未来没有费用"。
4. **未知日期**：维持既有规则——未知日期打断连续有效观测后缀；后缀不足 3 天直接拒绝
   预测（既有行为），不做补零。

按 3/7/13/14/21 天样例固定（`ForecastHistoryEvidenceTest`）：

| 连续观测净额形态 | 资格 | 结果 |
| --- | --- | --- |
| 每日 +2.00（3–29 天） | 可用 | 原有算法与置信度不变 |
| 每日 -1.00（3 天，冲正主导） | 不可用 | 不落快照，forecastAvailable=false |
| 正负混合、窗口净额 > 0 | 可用 | 净额口径照常外推 |
| 正负混合、窗口净额 ≤ 0 | 不可用 | 不落快照 |
| 全零观测（真实零/复核零） | 可用 | 预测 0：日均净额不大于 0，但方向明确为"无消费"——此时零是**有证据的零**，不是截零 |
| 未知日期打断后缀 | 按后缀重算 | 不足 3 天拒绝 |

"全零观测预测 0"与"冲正主导不可用"的区别依据：全零的窗口均值与总和都为 0，样本方向
一致且无反向证据；冲正主导的窗口日均 ≤ 0 意味着近期水平本身非正，外推为负没有业务含义，
外推为正没有证据。两者都不会产出负金额。

## 实现

- `ForecastEngine.forecast`：计算连续观测后缀的窗口净额。净额 < 0 或（净额 == 0 且
  存在负金额观测）时抛 `ForecastUnavailableException`（新增的领域信号，区别于
  IllegalArgumentException 的输入错误）；净额 == 0 且无负观测（全零历史）返回预测 0。
  RECENT_RUN_RATE 与 DAMPED_HOLT 共用同一资格判断，Holt 路径保留非负截断作为数值
  保险（平滑可能产生极小负数），不再出现语义不一致。
- `CostIntelligenceAnalysisService.persistForecast`：捕获
  `ForecastUnavailableException`，不写快照行（与"历史不足"同路径，但原因不同——
  通过 run 级诊断无法区分是设计使然，保持缺席即不可用的既有契约）。
- `BudgetRiskService`：校验不变。它继续拒绝负分量——这是防止未来回归混入负金额的
  最后防线，不是本次修复点。

## 版本升级（RUN_VERSION 3 → 4）

输入指纹的 canonical 串包含算法版本，唯一键为
`(org_id, analysis_date, currency, run_version, input_fingerprint)`。版本升到 4 后：

- 当前查询（summary/anomalies/forecasts/recommendations/budget-risk/Advisor 证据）只消费
  version=4 且指纹匹配的 run；旧 version=3 快照自然退出，不删除、不改写；
- 已 COMPLETED 的 v3 任务保持原样（历史声明与审计完整）；同输入的 v4 分析生成新 run；
- 无数据库迁移：唯一键已覆盖新版本行，`input_fingerprint` 默认值行为不变。

## 页面口径

- 预测页与总览把 `projectedAmount` 表述为**剩余期间预计费用**（它只是剩余天数的估计）；
  预算风险的 `projectedPeriodEnd` 保留**预计周期末**（actual + committed + forecast）。
- LOW/MEDIUM/HOLD 明确标注为**历史样本等级**（连续有效观测天数门槛），尚未经过
  回测准确率校准，不代表预测误差保证。
