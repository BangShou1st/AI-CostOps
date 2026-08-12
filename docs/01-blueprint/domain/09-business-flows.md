# 09. Core Business Flows

## Flow 0 — Registration / Login

### Demo Mode

```text
User
→ Register
→ Verify
→ Create User/Credential
→ Login
```

### Enterprise Mode

```text
Organization Admin
→ Invite
→ User accepts invitation
→ Set credential
→ Join Organization
→ Login
```

### Login

```text
username/email + password
→ login rate limit
→ verify credential
→ verify account status
→ issue short-lived Access JWT
→ create Refresh Session in Redis
```

### Refresh

```text
Refresh Token
→ locate Redis session
→ verify token hash/session/user state
→ rotate token
→ issue new Access JWT
```

### Logout

```text
delete/revoke Refresh Session
```

### Account Disable / Password Reset

```text
disable or reset
→ increment security version / revoke sessions
→ old Refresh Token invalid
```

Redis failure must not mutate financial state.


## Flow A — 企业统一 Provider Statement 导入

### Actors

```text
Finance / FinOps
System
Project Owner (optional review)
```

### Happy Path

```text
1. Finance 上传 DeepSeek/Kimi/GLM/MiMo/OpenAI Evidence
2. 系统创建 Evidence + ImportBatch
3. 计算 checksum / schema fingerprint
4. Provider Adapter 解析 RawProviderRecord
5. Validation
6. Normalize 成 Consumption/Charge candidates
7. Attribution rules 运行
8. 无法归属的进入 Review Queue
9. Finance / Project Owner 确认
10. 创建 Ledger posting
11. 写 Audit
```

### Failure Paths

#### Duplicate

```text
检测到业务重复
→ 标记 DUPLICATE_CANDIDATE
→ 不直接 POST
```

#### Partial parse error

```text
Import FAILED / PARTIAL_FAILURE
→ Raw evidence 保留
→ fix parser/config
→ retry same batch
```

#### Unknown provider field

```text
非关键新列
→ raw metadata 保留
→ import 可继续

关键字段缺失
→ validation failure
```

---

## Flow B — 员工个人 AI 费用报销

```text
Employee
→ Upload official statement/invoice
→ Create Expense Claim
→ Select Project / Cost Center
→ Policy validation
→ Approval
→ Finance review
→ Ledger posting
```

### 关键区别

Employee 提交：

> Evidence Submission

不是：

> Accounting Truth

### 例

```text
Claude/Kimi/other tool subscription
Employee paid: CNY 300
Project: WebPilot
```

系统：

1. 检查 evidence；
2. 检查是否重复；
3. 检查预算；
4. 审批；
5. 正式入账。

---

## Flow C — Attribution Review

### Input

```text
ChargeFact
Provider Hint:
api_key = sk-xxx
provider project = p-123
```

### Rule Engine

```text
Rule v4:
provider=DEEPSEEK
api_key_fingerprint=abc
→ Internal Project = WebPilot
→ CostCenter = R&D-AI
```

如果无规则：

```text
UNALLOCATED
→ manual review
```

Manual Decision 不能修改 Raw Fact，只新增 Allocation Decision。

---

## Flow D — Reconciliation

### 两类输入

```text
Internal Ledger
```

与：

```text
Provider Statement / Invoice
```

### Match Keys

根据 Provider 粒度动态选择：

```text
provider
billing period
account/org
project
line item
currency
```

不能强制 request-level。

### Result

```text
MATCHED
WITHIN_TOLERANCE
DIFFERENCE
```

DIFFERENCE：

```text
→ Reconciliation Case
→ classify
→ attach explanation/evidence
→ resolve
```

Resolve 后：

- 可能无需 Ledger correction（例如 rounding）；
- 可能需要 Adjustment；
- 可能需要等待 Provider 新账单。

---

## Flow E — Billing Period Close

```text
Finance
→ Start Closing
```

系统生成 Close Checklist：

```text
1. 所有 Import 已终态？
2. 有无 Unallocated material Charge？
3. 有无 Pending critical Expense？
4. 有无未解决 material Reconciliation？
5. 有无未完成 Adjustment approval？
```

全部通过：

```text
CLOSED
```

否则：

```text
Close blocked
→ 返回具体 blocker
```

---

## Flow F — Closed Period Correction

### 场景

8 月已经 CLOSED。

9 月发现：

```text
WebPilot +100
```

应该归属 AI-Collab。

默认：

```text
Create Correction Request
→ Approval
→ append
   WebPilot  -100
   AI-Collab +100
→ current view updated
```

如果企业政策必须重开：

```text
Reopen Request
→ Finance Admin approval
→ Audit
```

V1 默认优先 Adjustment，减少 reopen。

---

## Flow G — Budget Commitment

### 场景

```text
Project Budget = 10,000
Actual posted = 2,000
Outstanding commitments = 3,000
Available = 5,000
```

两个 Commitment 激活请求并发：

```text
A = 4,000
B = 3,000
```

系统必须确保：

> 最多一个成功；不能让 `actual + outstanding commitments` 超过 Total。

另外，后到的**已发生 Provider cost** 不因为预算不足而丢弃，它仍进入 Ledger，并显式形成 over-budget 状态。

实现必须使用数据库约束/锁/原子更新等可证明机制，而不是 Controller 先 `select` 再 `save`。

---

# V2 Preview — Real-time AI Gateway

不进入 V1 开发范围，但领域接口预留合理性。

```text
Application
→ AI Gateway
→ Internal Credential
→ Identity / Project
→ Budget Reservation
→ Provider
→ Streaming Response
→ Usage Capture
→ Settlement
→ Existing Ledger
```

原则：

- 只治理能代理的 API Traffic；
- 不承诺控制所有 SaaS/Coding Plan；
- Gateway 产生的新事实复用 V1 Ledger；
- 默认不保存 Prompt/Response 正文。

这样 V2 是新增 Evidence/Telemetry Source，不是推倒 V1。
