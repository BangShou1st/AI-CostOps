# 01. Research Baseline

> 日期：2026-08-11
> 用途：冻结项目事实基础，防止后续把设计假设误传成 Provider/企业事实。

## 1. 研究问题

本轮调研只回答四个问题：

1. 多 Provider AI 成本治理是不是现实问题？
2. Provider 最终能提供怎样的 Usage / Cost / Billing 数据？
3. 哪些财务语义已有成熟行业标准可以借鉴？
4. 哪些内容仍没有证据，必须留作假设或后续验证？

本轮**不追求**：

- 穷举所有 AI Provider；
- 复刻各家控制台；
- 证明某一种企业审批流程是行业唯一标准；
- 提前确定数据库表；
- 提前选择微服务、Kafka、Redis 等技术栈。

---

## 2. Evidence Level

项目所有事实按证据等级标注：

| Level | 定义 | 当前可用性 |
|---|---|---|
| **E1 — Official Documentation** | Provider、FOCUS、FinOps Foundation 官方公开说明 | 充足 |
| **E2 — Real Export Format** | 从真实平台实际导出的 CSV/XLSX/ZIP/UI 格式 | 五家已覆盖 |
| **E3 — Populated Account Record** | 导出中存在真实账户级非空记录 | MiMo/GLM 有少量；不能视为企业样本 |
| **E4 — Enterprise Production Validation** | 真实公司、多团队、多项目、长期财务/生产运行验证 | **没有** |

### 研究口径

允许：

> “OpenAI 官方支持 Activity / Cost 两类导出。”

允许：

> “MiMo 的真实导出中区分 cache hit、cache miss、output Token 和对应 Amount。”

不允许：

> “企业通常一定按我们设计的审批链走。”

不允许：

> “Kimi 的赠送账户消耗一定等价于 FOCUS Credit 行。”

后两类只能作为设计方案或候选映射。

---

## 3. 已检查的本地 Provider Evidence

以下文件由真实 Provider 平台导出或来自真实平台操作过程。SHA-256 用于后续确保研究输入未被替换。

### DeepSeek

`usage_data_2026-07-13_2026-08-11.zip`

SHA-256:

`abe8dbb983d40ec0ee2dcecb943c2f6363f89430bed1bfcee7e0697425392d62`

内容：

`amount-2026-07-13_2026-08-11.csv`

```text
user_id
start_time_iso
end_time_iso
model
api_key_name
api_key
type
price
amount
```

`cost-2026-07-13_2026-08-11.csv`

```text
user_id
start_time_iso
end_time_iso
model
wallet_type
cost
currency
```

当前两文件均为 0 条记录，因此：

- Header 属于 E2；
- `wallet_type` 的具体枚举不得猜测；
- 不能从此文件证明 `amount` 与 `cost` 的一一对应关系。

DeepSeek 官方 FAQ 另外明确：Usage 页面按月导出的压缩包包含两个 CSV，`amount` 文件可按 API Key 查看用量明细。

---

### Kimi / Moonshot

`moonshot开放平台账单_20250301-20260131_e4c7b13e.xlsx`

SHA-256:

`013549cf4359037d0a9b0cbc4285533ec37028c111734a4519bb9c3f99e16c40`

Sheet: `账单汇总`

```text
时间范围
用户ID
组织ID
客户主体
充值账户消耗（元）
赠送账户消耗（元）
```

样本金额均为 0。

可确认：

- 存在 User / Organization 级账单身份；
- 导出把“充值账户消耗”和“赠送账户消耗”拆开。

不能确认：

- 两者在内部账务上应该怎样记账；
- 是否存在更细的模型/API Key 级导出；
- 赠送账户消耗在所有情况下是否等价于内部 `PROMOTIONAL_CREDIT`。

Kimi 官方组织文档另确认：

- Organization 下可建立 Project；
- Project 下创建 API Key；
- Project API Key 消费记在项目消费中；
- 可配置项目预算、消费提醒和成员/API Key 管理。

这些是 E1，与本地汇总账单的粒度不同。

---

### GLM / 智谱

`智谱AI开放平台月度账单2026-03-2026-08_1786455853811.xlsx`

SHA-256:

`e1f6f7be6f3064411fe680403983cf613ac2122bc13d65d0306ccc3ea9110b23`

字段：

```text
账期(月)
目录总价
总消费金额
信用支付金额
赠金抵扣金额
应付金额
已付款金额
待付款金额
结算状态
```

文件包含 2 行账户级结算记录，但出现例如：

```text
总消费金额 = 0
已付款金额 > 0
待付款金额 < 0
```

因此：

- 可以把 Header 和“存在结算状态/应付/已付/抵扣等字段”作为证据；
- **不能从两行样本反推 GLM 完整结算公式。**

官方文档补充确认：

- 平台存在费用明细与汇总账单；
- API 计费可能按 Token，也存在按次数计费的产品；
- 可通过现金余额或资源包扣减；
- 发票口径强调“实际消耗的现金金额”。

---

### MiMo

`usage_data_20260801_20260831_1315186008.xlsx`

SHA-256:

`ea37b0db8d1d5914aec31f76e1739107cd9d68e4a069f1b84fac18507ec3dd84`

Sheet: `Model usage detail`

```text
Date
Model
API Key
Currency
Consumed Amount
Input Hit Amount
Input Miss Amount
Output Amount
Total Tokens
Input Hit Tokens
Input Miss Tokens
Output Tokens
Total audio duration
Request Count
```

样本包含 3 行实际账户级 `mimo-v2.5` 使用记录。

Sheet: `Plugin usage detail`

```text
Date
Plugin
API Key
Currency
Consumed Amount
Request Count
```

该 Sheet 当前为空。

MiMo 官方文档确认：

- Usage 页面可查看并导出 Token 消耗和调用次数；
- Token Plan 使用 Credits；
- 不同模型对 cache-hit、cache-miss、output Token 的 Credit 转换率可不同；
- Token Plan 与普通 PAYG API 是不同购买/计费方式。

因此明确支持以下判断：

```text
Token quantity != Credit quantity != monetary cost
```

---

### OpenAI API

`completions_usage_2026-07-12_2026-08-11.csv`

SHA-256:

`1cef32926aefa8c2fe35fdd8ef0af98975052e58a127c28e7450dc2ece756a08`

`cost_2026-07-12_2026-08-11.csv`

SHA-256:

`1cef32926aefa8c2fe35fdd8ef0af98975052e58a127c28e7450dc2ece756a08`

两份当前文件内容相同，均为 31 个日 bucket：

```text
start_time
end_time
start_time_iso
end_time_iso
```

没有实际 API Usage / Cost 行。

结合官方文档可确认：

- API Usage Dashboard 区分 `Activity data` 和 `Cost data`；
- Activity 可以按 API capability 过滤，并可按 Project / User / API Key / Model 等维度分组；
- Organization Usage API 的 completions 结果可包含 input/output/cached tokens、request count、project/user/api-key/model/service tier 等；
- Organization Costs API 独立返回 monetary amount，当前官方契约支持按 project / line item 分组（2026-08-14 复核；更早研究中的按 api key 分组假设已移除，见 provider-mapping）。

因此当前本地 CSV 只能证明：

- 真实 Export UI/流程；
- 空 Usage 情况下存在日 bucket；
- **不能将 4 列 Header 当作非空 CSV 的固定完整契约。**

项目中将其称为：

> **OpenAI API — Responses / Chat Completions Activity/Cost Export**

而不是“Codex 账单”。

---

## 4. 行业问题是否真实存在

FinOps Foundation 已将 AI/Token 成本纳入独立实践：

- GenAI Cost Tracker 指南讨论 token 级成本跟踪、归属、集中式 hub/proxy 与分散式使用；
- Tokenomics / SaaS model token cost 实践强调 provider account、API key、payment method inventory、attribution、proxy layer、Crawl/Walk/Run 成熟路径；
- FinOps for AI 将 token/reporting/API usage data 视为重要成本数据源。

因此本项目的根问题有现实依据：

```text
Provider 原生身份/账单
          !=
企业内部项目 / 团队 / 成本中心 / 业务用途
```

以及：

```text
企业 AI 支出可能同时来自
API PAYG
Subscription
Coding Plan
Seat
Credits
个人垫付
企业统一采购
```

本项目并不声称这是未被市场发现的新问题；相反，它是正在形成中的 AI FinOps / token economics 实践。

---

## 5. FOCUS 对本项目有什么意义

当前正式版 FOCUS 1.4 于 2026-06-04 通过。

FOCUS 1.4 明确增加：

- Invoice Detail dataset；
- Billing Period dataset；
- Cost & Usage 到 Invoice 的 reconciliation 支持。

FOCUS 还明确区分：

- `ConsumedQuantity / ConsumedUnit`：资源真实消耗；
- `PricingQuantity / PricingUnit`：Provider 定价使用的数量/单位；
- Cost metrics；
- Charge Category / Correction 等账务语义。

这些概念与 AI Provider 的异构数据高度相关。

### 但本项目不宣称 FOCUS 合规

原因：

1. AI CostOps 不是通用云/SaaS FOCUS ETL；
2. 我们需要 AI 特有的 token/cache/credit/provider-workload 等语义；
3. 当前没有做 FOCUS conformance validator；
4. Provider Adapter 输入也不保证是 FOCUS 数据。

正确描述是：

> **FOCUS-inspired accounting semantics**

---

## 6. 已验证的跨 Provider 共同事实

### F-001：Consumption 与 Money 不能合并

证据：

- DeepSeek `amount` 与 `cost` 分离；
- OpenAI Activity 与 Cost 分离；
- MiMo 同时存在 Token、Amount；
- MiMo Token Plan 又引入 Credit。

设计影响：

> Canonical Model 不使用一张万能 `provider, tokens, cost` 表作为核心领域模型。

---

### F-002：Provider 数据粒度不一致

已观察到：

- MiMo：Date + Model + API Key；
- Kimi：时间范围 + User + Organization 汇总；
- GLM：月账期结算汇总；
- OpenAI：可按 day + project/user/key/model 聚合；
- DeepSeek：user/model/key/type 维度。

设计影响：

> Canonical Fact 必须携带 `grain`，身份维度允许缺失；不能强制每条记录都有 employee/project/api_key/model。

---

### F-003：Provider Identity 只是 Attribution Hint

例如：

```text
provider api_key = wp-prod
```

最多说明 Provider 侧 Key。

企业最终需要的是：

```text
Application = WebPilot
Project = WebPilot
Team = AI Platform
CostCenter = R&D-03
```

这个映射属于企业内部 Attribution Domain，不应由 Provider Adapter 偷偷决定。

---

### F-004：Billing / Settlement 与 Usage 不是同一对象

证据：

- GLM 汇总账包含 payable / paid / outstanding / settlement；
- FOCUS 1.4 将 Cost & Usage 和 Invoice Detail 分为不同 dataset；
- OpenAI 推荐用 Cost data 做 invoice-like breakdown/reconciliation。

设计影响：

> Statement / Invoice 与 Metering/Charge Facts 分开建模。

---

## 7. 设计基线

基于当前研究，V1 采用：

```text
Evidence
  ↓
Raw Provider Record
  ↓
Consumption / Pricing / Charge
  ↓
Attribution
  ↓
Ledger
  ↓
Reconciliation
  ↓
Billing Period Close
```

外围能力：

```text
Budget
Expense Claim
Approval
Audit
```

V2 才引入：

```text
AI Gateway
Budget Reservation
Realtime Metering
Settlement
```

---

## 8. 明确未知 / Open Questions

以下内容在实现中不得当成事实：

1. DeepSeek `wallet_type` 的实际枚举全集；
2. DeepSeek amount 与 cost 的真实匹配规则；
3. Kimi 赠送账户消耗如何映射成内部会计分录；
4. Kimi 是否存在我们未拿到的更细账单 export；
5. GLM 少量样本中异常金额关系的完整原因；
6. OpenAI 非空 CSV 的固定 Header 契约；
7. 各 Provider 在导出中如何表达 refund / late usage / correction / tax 的全部情况；
8. 企业内部审批层级的“标准答案”；
9. 真实企业的 Cost Center 组织结构；
10. 真实生产规模下的性能分布。

这些问题通过：

- Provider Adapter versioning；
- Raw Evidence retention；
- Synthetic test cases；
- 后续真实消费样本；
- 实现后的运行数据

逐步验证。

---

## 9. 允许对外描述的真实性口径

推荐：

> AI CostOps 的领域模型基于 DeepSeek、Kimi、GLM、MiMo、OpenAI 五家主流 AI Provider 的官方导出格式和公开计费规则，并参考 FinOps Foundation GenAI 成本治理实践及 FOCUS 账务语义设计。由于无法取得企业内部敏感财务数据，项目通过公开规则、实际账户导出格式和合成企业数据验证业务不变量，不声称经过真实企业生产环境验证。

不推荐：

> “真实企业项目数据驱动设计。”

不推荐：

> “完整复刻真实公司财务系统。”

不推荐：

> “FOCUS 1.4 compliant。”
