# 06. Domain Glossary

> 目标：让产品、代码、测试、README 使用同一套语言。

## Provider

提供 AI 模型、Coding Plan、API 或相关服务的外部厂商。

例：

```text
OPENAI
DEEPSEEK
KIMI
GLM
MIMO
```

`Codex` 不自动等于 Provider；它可以是 OpenAI 下的 product/workload。

---

## Provider Account / Organization / Project / User / API Key

Provider 自己的身份/资源层级。

这些值属于：

> **Attribution Hint**

不自动等于企业内部 Employee / Team / Project / Cost Center。

---

## Evidence

支撑一项外部成本/使用事实的原始证据。

例：

- CSV；
- XLSX；
- ZIP；
- Statement；
- Invoice；
- V2 Gateway telemetry。

Evidence 上传成功：

> **不等于正式入账。**

---

## Import Batch

一次 Evidence 导入生命周期。

候选状态：

```text
UPLOADED
PARSING
VALIDATING
NORMALIZING
READY_FOR_REVIEW
CONFIRMED
FAILED
```

是否把 `POSTED` 放在 ImportBatch 上由实现时决定；更推荐 Posting 属于 Ledger workflow。

---

## Raw Provider Record

从 Provider Evidence 中解析出来的原始行/对象。

目的：

- 保留外部事实；
- 支持 parser 重放；
- 支持 schema evolution；
- 防止错误 normalize 后无法恢复。

---

## Consumption Fact

表达：

> **实际消耗了什么资源。**

典型字段：

```text
meter
quantity
unit
period
grain
provider identity dimensions
```

示例：

```text
meter = INPUT_CACHE_HIT_TOKEN
quantity = 20_000_000
unit = TOKEN
```

---

## Pricing Fact

表达：

> Provider 如何把消耗转换成计价数量/单位/单价。

并非每个 Provider Evidence 都能产生 Pricing Fact。

缺少依据时：

> 保持 UNKNOWN / absent，不反推。

---

## Charge Fact

Provider 报告的费用/抵扣/购买/调整事实。

借鉴 FOCUS 的类别：

```text
USAGE
PURCHASE
CREDIT
TAX
ADJUSTMENT
OTHER
```

Charge Fact 仍然不是企业正式 Ledger。

---

## Funding Source

这笔成本/额度由什么来源承担。

候选：

```text
CASH
PROMOTIONAL
RESOURCE_PACKAGE
SUBSCRIPTION_ALLOWANCE
CREDIT_BALANCE
UNKNOWN
```

只有 Provider 证据明确时才设置。

---

## Attribution Hint

Provider 提供的归属线索。

例：

```text
api_key
provider_project
provider_user
organization
model
```

---

## Allocation / Cost Attribution

企业内部正式决定：

> 这笔成本应该由谁承担。

典型目标：

```text
Employee
Team
Internal Project
Application
Cost Center
```

必须记录：

- 来源；
- rule/version；
- actor；
- timestamp；
- reason。

---

## Cost Center

企业内部成本归属单元。

Cost Center 是我们模拟企业业务时定义的组织概念，不宣称与某一家公司的财务结构一致。

---

## Expense Claim

员工个人垫付 AI 工具后提交的费用申请。

Expense Claim：

> 不等于 Ledger Entry。

必须经过 Evidence、Policy、Approval 等流程。

---

## Approval Case

对：

- Expense；
- Budget commitment；
- correction；
- reopen period

等需要人工审批的业务请求进行状态管理。

---

## Budget

某个时间周期、组织/项目/成本中心可使用的成本上限。

---

## Budget Commitment

已批准但尚未完全成为 Actual Cost 的预算承诺。

V1：

```text
Total
Committed
Actual
Available
```

V2 增加：

```text
Reserved
```

用于进行中的实时 API 请求。

---

## Ledger

企业内部 AI 成本的正式账。

### Ledger Entry

已经确认的内部账务事实。

关键原则：

> POSTED 后不可 destructive update。

---

## Adjustment / Correction

用于修正历史已入账事实的追加记录。

不是：

```sql
UPDATE old_entry
```

而是：

```text
old entry remains
+
correction entries
=
current accounting view
```

---

## Statement

Provider 给出的周期性费用汇总/对账材料。

可能不是正式税务发票。

---

## Invoice

Provider 正式开具的应付/付款凭证或 invoice-level detail。

Statement 与 Invoice 不强制合并成同一对象。

---

## Billing Period

由企业内部管理的结算周期。

候选状态：

```text
OPEN
CLOSING
CLOSED
```

这里的状态机是 AI CostOps 自己的工作流，并非 FOCUS 强制定义。

---

## Reconciliation

把内部账与外部 Provider Statement/Invoice 做核对的过程。

---

## Reconciliation Case

无法自动匹配/解释的差异。

候选原因：

```text
MISSING_INTERNAL
MISSING_EXTERNAL
DUPLICATE
PROMOTIONAL_CREDIT
REFUND
TAX
FX
ROUNDING
LATE_USAGE
UNKNOWN
```

这些是内部 taxonomy，不声称 Provider 原生使用相同枚举。

---

## Provider Adapter

隔离外部 Provider schema 的 Anti-Corruption Layer。

负责：

- detect source；
- parse；
- validate；
- emit raw/normalized candidate facts。

不负责：

- 决定内部项目归属；
- 自动 POST Ledger；
- 偷偷补全无证据的价格/付款语义。

---

## Import Fingerprint

用于业务幂等/重叠检测的指纹。

不能只依赖：

> 文件名。

也不应该只依赖：

> 文件 SHA-256。

因为同一账单重新保存可能改变文件 hash。

---

## Audit Event

记录重要业务行为：

- actor；
- action；
- object；
- before/after reference；
- reason；
- time。

Audit 不保存 secret 或 Prompt/Response 正文。


---

# IAM / Runtime Glossary

## User

AI CostOps 本地身份主体。User 不自动等于 Provider User。

## Credential

用户认证凭据，例如密码哈希。不保存明文密码。

## Access Token

短生命周期访问凭据。V1 设计采用 JWT，由 Spring Security 校验。

## Refresh Session

服务器端可撤销登录会话。V1 存储在 Redis，并带 TTL。

推荐只保存 token hash 与 session metadata，不保存可直接复用的明文 Refresh Token。

## Role

业务角色，例如：

```text
EMPLOYEE
PROJECT_OWNER
FINANCE_REVIEWER
FINANCE_ADMIN
SYSTEM_ADMIN
```

## Permission

细粒度动作权限。Role 是 Permission 的集合，不与 Data Scope 混为一谈。

## Data Scope

限制用户可以对哪些业务数据执行权限。

候选：

```text
SELF
PROJECT
TEAM
COST_CENTER
ALL_FINANCE
```

## Security Version

用于让现存 Access Token 快速失效的安全纪元。账号禁用、密码重置、重大权限变化时可递增。

## Redis Cache

非权威、可丢失的运行时数据。

例如：

```text
refresh session
verification code
rate limit counter
permission context cache
dashboard cache
```

Redis 不是 Ledger/Budget/BillingPeriod 的 Source of Truth。
