# 2026-10-13 维护记录：预测资格修复与文件账单主链审查

基线：`codex/history-coverage-20261012@435b0bd`（三本地提交保持未发布）。本次追加两个提交
（`8abb96f` 修复与口径、`724e3d6` 断言对齐与记录）；未跟踪的 `.freebuff/`、`CHANGELOG.md`、
`LICENSE` 保持原样，无 reset/clean。

> 2026-10-13 交付复核后补充修正：本记录初版混用了两次完整 verify 的数字（见验证范围表
> 更正），且预测资格规则在复核中发现两处遗漏（外推窗口资格、合法负 actual），已在同日
> 后续提交修复并升级 RUN_VERSION 至 5，见文末"交付复核补充"一节。

## 阶段 A：预测负金额故障修复与展示口径

### 问题与复现

连续 3 天实际净额各 -1 CNY 的短历史会生成 -20 CNY 预测（RECENT_RUN_RATE 无符号限制），
`BudgetRiskService` 拒绝负分量导致消费链失败。本次补齐了此前缺失的真实消费链复现：

- `NegativeLedgerConsumptionChainIntegrationTest`（真实 MySQL + MockMvc + Advisor）：
  - 夹具经 `RECONCILIATION_ADJUSTMENT` 血统写入 3 天 -1 CNY（Gateway 结算 CHECK 强制非负，
    负净额的现实来源只有冲正/对账调整——修复前夹具曾误用 gateway_settlement 被约束拒绝，
    这本身确认了负净额的合法入口）；
  - 修复前：`POST /analyze` 落负快照、`GET /budget-risks` 500、Advisor
    `requestExplanation` 整体回滚；修复后：分析 COMPLETED、无任何 forecast 快照行、
    风险 200 且 `forecastAvailable=false`、Advisor 证据 201 且冻结值一致、账本保持 -3 CNY。

### 规则选择（详见 2026-10-13-forecast-eligibility-negative-net.md）

- 带符号账本事实保留，不截零；
- 连续观测窗口净额 < 0，或净额 = 0 且含负观测（冲正主导）→ 预测不可用
  （新领域信号 `ForecastEngine.ForecastUnavailableException`，不落快照，
  消费端 `forecastAvailable=false`）；
- 全零观测历史 → 预测 0（有证据的零，区别于截零）；
- 正负混合按窗口净额判断；未知日期维持"打断连续后缀"既有规则。

### 版本与消费

`RUN_VERSION` 3→4。输入指纹含版本号，当前查询自动退出 v3 快照；无数据库迁移
（唯一键已含版本），历史快照与审计原样保留。

### 页面口径

- 预测页/总览：`projectedAmount` 改称**剩余期间预计费用**；
- `projectedPeriodEnd` 保留**预计周期末**；
- LOW/MEDIUM/HIGH 全部标注**历史样本等级，未经准确率校准**；页面不再出现"置信度"字样；
- 方法说明补冲正主导缺席语义。API 字段未改名，契约无破坏性变更。

## 阶段 B：文件账单财务主链入口审查

五个入口逐项审查完成，**未发现需要修复的缺陷**，依据与证据记录在
`2026-10-13-billing-chain-entry-review.md`：

| 入口 | 关键证据 |
| --- | --- |
| 导入确认 | 条件更新+语义重确认幂等+关账双门（组织准入+按 attempt 账期）；审计回滚测试 |
| 重复处置 | keep 不改财务事实故不需要组织门；exclude 有完整关账门与 CLOSED 拒绝测试；锁序 org→period→charge |
| 分摊确认 | 权限门在幂等 reserve 之前（撤权不可重放绕过）；锁序 source→decision→lines；金额精确守恒；审计/指针回滚测试 |
| Provider 入账 | postingKey 唯一键+并发只产生一次入账；冻结锁序 period→budgets→commitments→source→decision→lines；混合门；关账竞争双向测试 |
| 预算/承诺 | actual 允许负（设计注释明确）；承诺消费 lineage 幂等+终态重放+双消费防护+条件更新 |

跨入口：组织隔离（全谓词 + 404 不泄露）、币种行级匹配、DECIMAL(20,8)+decimal-string、
成功审计与财务动作同事务。已复跑代表回归（allocation/ledger/duplicate 系列）通过。

## 附带修正（435b0bd 的全量 verify 欠账）

上次提交只跑了定向回归，本次完整 verify 暴露 6 项断言未随 V28/V29 更新，已修正
（非业务变更，仅测试期望对齐已发布迁移）：

- `RolePermissionSeedIntegrationTest`：权限目录 50→51、FINANCE_ADMIN 增加 `COST_COVERAGE_MANAGE`；
- `IamReadApiIntegrationTest`：权限总数 50→51；
- `MeApiIntegrationTest`：两处 me 权限列表补 `COST_COVERAGE_MANAGE`（字母序）；
- `GatewayM12ReservationSchemaIntegrationTest`：迁移计数 27→29。

## 本次验证范围（绑定被验证代码 = 本次工作区 HEAD）

| 验证 | 结果 |
| --- | --- |
| 负金额消费链集成（MySQL/API/Advisor，2 项） | 通过 |
| intelligence 领域单测（5 类，36 项含新资格规则） | 通过 |
| intelligence 定向集成回归（8 类 34 项） | 通过 |
| Advisor 集成回归（3 类 13 项） | 通过 |
| 完整 backend verify（Failsafe 集成 1208 项） | 1182 通过、0 failures、26 errors——26 项为 V10/V11 迁移测试的 JDBC 连接超时（错误时间 17:45–18:06，与本机 dev 三端启动 17:38 及浏览器验收窗口重合；资源竞争是合理假设，时间重合与定向重跑通过支持但不能单独证明全部根因）。随后在同一代码上单独重跑 V10（10 项）+ V11（16 项）全部通过。初版记录误写为"1202 通过 + 26 错误"，1202 来自另一轮有 6 项断言失败的运行，两者不能混用；1208 是 Failsafe 集成数量，不是单元与集成合计 |
| `npm run test`：脚本测试 + backend 单元/架构 637 项 + gateway 174 项 + 前端 547 项 | 全部通过 |
| 前端 lint + 生产 build（tsc+vite） | 通过 |
| 启动验证：Testcontainers 迁移（29 版）+ 本机三端 `npm run dev`（backend/gateway/frontend 全部 200） | 通过 |
| browser-skill 页面验收（合成数据，bsk 后台会话） | 通过：方法口径含冲正主导说明；英雄区"组织剩余期间预计费用"+`LOW（历史样本等级，未经准确率校准）`；列表行"LOW（样本等级）"+"剩余期间预计费用"；总览"剩余期间预计费用（… 样本等级）"；覆盖确认→LOW 零预测生成→撤销→预测消失全流程；全文无"置信度" |

## 边界与遗留

- 真实 Provider/OpenCode 联调、独立浏览器组织隔离验收：维持 DEFERRED；
- 远程 CI：未授权推送/PR，未执行；
- `.env`、MinIO 卷属主修复（65532）、gateway dev 密钥均为本机验收环境配置，不入库；
- 阶段 B 审查的两条记录在案设计选择（replaceLines 无幂等键、keep 依赖行锁）不构成缺陷，
  后续批次如触及可再评估；
- 撤销后本地合成数据已清（三天覆盖全部 REVOKED），dev 环境进程已停止。

## 交付复核补充（同日，RUN_VERSION 5）

交付复核发现两处消费边界遗漏（组件级复现成立），已在后续提交修复：

1. **资格窗口与外推窗口不一致**（P1）：RECENT_RUN_RATE 只外推最近 7 天，但资格检查此前
   只覆盖完整连续后缀。复现：6 天 +10 + 7 天 -1（整体净 +53 通过资格，尾部净 -7 外推出
   -20 CNY）。修复：`ForecastEngine` 对实际计算窗口（尾部 7 天窗口）单独应用
   `requireExtrapolatableHistory`；DAMPED_HOLT 路径维持全后缀检查。
2. **合法负预算 actual 被风险算法拒绝**（P1）：V11 schema 有意允许 credits/reversals 使
   `actual_amount < 0`，但 `BudgetRiskService` 统一拒绝所有负分量；组件复现 actual=-1
   无预测仍抛异常。修复：`actual` 按带符号净额参与敞口累计；承诺、预占、未来消费、总额
   仍是纯敞口分量，继续拒绝负值。
3. **RUN_VERSION 4→5**：资格语义再次变化，旧 v4 快照经含版本指纹自动退出当前消费。
4. **消费链补测**：`NegativeLedgerConsumptionChainIntegrationTest` 增至 4 项——新增
   "正整体历史 + 负尾部不落预测且风险链可用"（API 层复现复核场景）与"合法负 actual 经
   风险 API 与 Advisor 冻结证据返回 -1.00000000 敞口"；领域测试新增双窗口组合与负
   actual/非负分量断言（`ForecastHistoryEvidenceTest`、`CostIntelligenceEnginesTest`）。
5. **记录更正**：预测资格文档中"合法零历史不生成预测"的矛盾描述（与表格/源码/测试相反）、
   "LOW/MEDIUM/HOLD"笔误与残留"置信度"措辞已更正；本记录初版的 verify 计数混用已按
   Failsafe 原始报告更正（见上表）。复核同时指出"Gateway 结算非负 CHECK 不足以推出所有
   账本负金额只能来自冲正/对账调整（Provider 入账另有 CREDIT 分支）"，夹具注释已按完整
   血统口径修正理解，正文相应表述以血统枚举为准。
