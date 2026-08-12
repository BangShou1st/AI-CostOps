# 05. Product Scope

## 1. 产品定义

**AI CostOps** 是面向研发团队的多 AI Provider 成本归集、费用核算与治理平台。

V1 解决：

> **已经发生的 AI 消费，企业如何变成可信、可归属、可审批、可对账、可关账的一本内部成本账。**

V2 扩展：

> **对于企业能够控制的 API 流量，如何通过统一 AI Gateway 在请求发生时进行身份识别、预算预占、Usage Metering 和实时成本结算。**

最终：

```text
Post-billing imports ----\
                          >---- Cost Ledger ---- Governance
Realtime Gateway --------/
```

---

## 2. 目标用户

### Employee / Expense Submitter

- 注册/接受企业邀请并登录；
- 上传个人垫付的 AI 工具账单/发票；
- 选择业务用途；
- 提交费用报销；
- 查看自己的费用和处理结果。

### Project Owner / Team Lead

- 查看项目 AI 成本；
- 审核归属；
- 审批预算/费用；
- 管理项目成员；
- 解释超预算。

### FinOps / Finance Reviewer

- 导入企业统一 Provider Statement/Invoice；
- 查看未归属费用；
- 处理对账差异；
- 执行 Billing Period Close；
- 审计调整。

### System / Organization Admin

- 组织、用户、团队、项目、角色；
- Provider adapter/configuration；
- Attribution rules；
- 登录安全配置；
- 不负责直接篡改 Ledger。

---

## 3. V1 核心场景

### UC-00 IAM / Login

```text
Registration / Invitation
→ Login
→ Access Token
→ Refresh Session
→ Role/Data Scope
```

支持两种模式：

```text
Demo:
public registration enabled

Enterprise:
organization invitation only
```

通过配置切换。

### UC-01 企业统一 Provider 账单导入

```text
Finance/Admin
→ upload Provider statement
→ parse / validate / normalize
→ allocate
→ post
→ reconcile
```

### UC-02 员工个人 AI 费用报销

```text
Employee
→ upload official statement/invoice
→ expense claim
→ policy check
→ approval
→ ledger posting
```

### UC-03 无法自动归属的 Cost

```text
Raw Cost
→ attribution unresolved
→ manual/rule resolution
→ approved allocation
→ post
```

### UC-04 Provider Statement / Invoice 对账

```text
Internal Ledger
vs
External Statement/Invoice
→ matched / difference case
```

### UC-05 月末关账

```text
OPEN
→ CLOSING
→ validate outstanding work
→ CLOSED
```

### UC-06 关账后纠错

```text
Historical error
→ correction / adjustment
→ audit trail
```

### UC-07 项目预算

```text
Total Budget
→ commitment/approval
→ actual spend
→ variance
```

V1 预算是事前预算/承诺控制，**不是 Provider 实时断流**。

---

## 4. V1 必做

### IAM

- 注册/邀请；
- 登录、登出；
- Access Token + Refresh Session；
- 修改密码；
- 找回密码/一次性验证码；
- 管理员禁用账号；
- Role / Permission；
- Team / Project membership；
- Data Scope。

### CostOps Core

- Organization / Team / Project / Cost Center；
- Provider Evidence upload；
- 5 家研究 Provider 的 schema-aware adapter 基础支持；
- Import Batch 状态机；
- Raw Evidence/Raw Record 保留；
- Consumption / Charge normalization；
- Attribution rules + manual allocation；
- Expense Claim / Approval；
- Immutable Ledger；
- Adjustment / Correction；
- Budget / Commitment；
- Statement / Invoice；
- Reconciliation Case；
- Billing Period Close；
- Audit Trail。

### Engineering

- MySQL transaction / unique constraint；
- Redis session / TTL / login-rate-limit / permission cache；
- MinIO/S3 evidence storage；
- Docker Compose 一键启动；
- GitHub Actions；
- 可复现测试数据。

---

## 5. V1 明确不做

- AI Chatbot；
- RAG；
- Agent 工作台；
- Prompt 管理；
- 模型效果评测；
- Provider 自动切换；
- 实时 AI Gateway；
- 实时 Token 阻断；
- 实时模型路由；
- 完整 ERP/总账；
- 银行支付；
- 自动报税；
- 完整 FOCUS conformance；
- “支持所有 Provider”；
- 微服务拆分；
- 为展示技术而强制引入 Kafka/Elasticsearch。

---

## 6. 产品核心不是 Parser 或 Dashboard

错误定位：

```text
upload Excel
→ database
→ ECharts
```

正确定位：

```text
heterogeneous evidence
        ↓
trusted facts
        ↓
internal attribution
        ↓
immutable ledger
        ↓
reconciliation
        ↓
period close
```

---

## 7. V1 Success Criteria

V1 只有同时满足以下条件才算闭环：

1. 完成登录、刷新、注销、禁用用户的认证闭环；
2. Redis 中 Refresh Session 可主动撤销并有 TTL；
3. 至少五种 Provider 输入格式都有 schema fixture；
4. 至少三种 Provider 使用 populated synthetic fixture 跑完整 E2E；
5. 同一 Evidence 重复导入不产生重复正式账；
6. Import 中途失败可安全重试；
7. 无 Attribution 的 Charge 不能直接 POST；
8. POSTED Ledger 不允许直接修改；
9. Historical error 通过 Adjustment 修正；
10. Billing Period CLOSED 后普通写入被拒绝；
11. 未解决重大 Reconciliation Case 时禁止 Close；
12. Budget commitment 并发测试不存在超分配；
13. Redis 故障不能破坏 Ledger/Budget/BillingPeriod 的数据库正确性；
14. 所有关键状态变化有 Audit Trail；
15. `docker compose up -d` 能拉起完整 V1；
16. 测试和性能指标来自真实执行结果。

---

## 8. 非功能目标

### Correctness

```text
MySQL = source of truth
```

- Money 使用 BigDecimal/DECIMAL；
- Ledger posting 有 DB-level business uniqueness；
- Budget correctness 不依赖 Redis lock；
- 重要 Domain invariant 有自动测试。

### Recoverability

- Import job 可重试；
- Raw Evidence 不丢失；
- worker crash 不重复 posting；
- Redis session/cache 丢失时账务数据不丢失。

### Traceability

```text
Ledger
→ Allocation
→ Charge Fact
→ Raw Record
→ Evidence
```

### Security

- 密码强哈希；
- Refresh Token 服务端 session 管理；
- login/reset/verification 有 TTL 和 rate limit；
- Provider API Key 默认脱敏；
- 原始 Evidence 限权；
- Audit/Log 不记录 secret。

### Performance

设计目标：

- 500k normalized fact import 不 OOM；
- 1m fact 下月度聚合仍可用；
- 100 并发 Budget commitment 不超分配；
- Dashboard 热点统计可通过 Redis 短 TTL 缓存降低重复聚合成本。

具体耗时只在实测后记录。
