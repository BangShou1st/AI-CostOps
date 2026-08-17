# 06. 权限矩阵设计

## 1. 授权模型

一次授权判断由四部分组成：

```text
Authenticated Identity
+
Permission
+
Data Scope
+
Resource State Rule
```

Role 可以叠加。

重要原则：

> `SYSTEM_ADMIN` 不自动拥有 Finance Posting / Period Close 权限。

系统管理员可以管理用户，但如果要做财务动作，必须额外拥有对应 Finance Role。

## 2. V1 Role

### EMPLOYEE

默认组织成员。

可以：

```text
维护自己资料
创建自己的 Expense Claim
上传自己的 Expense Evidence
查看自己的 Claim
提交自己的 Claim
```

### PROJECT_OWNER

针对一个或多个 Project 的 Scoped Role。

可以：

```text
查看 Project Cost / Budget
参与 Project Allocation Review
按 Policy 审批 Project Workflow
管理被授权 Project Member
```

不能：

```text
Close Period
直接改 Ledger
```

### FINANCE_REVIEWER

可以：

```text
Provider Evidence Upload / Import Review
Duplicate Review
Allocation Confirm
Ledger Posting
Reconciliation
Expense Review
```

### FINANCE_ADMIN

在 Finance Reviewer 基础上增加：

```text
Budget Manage
Correction
Period Close
Period Reopen
Provider Finance Config
```

### SYSTEM_ADMIN

负责：

```text
User
Invitation
Role Assignment
Organization / Team / Project Master Data
```

不自动继承 Finance 权限。

## 3. Permission Catalog

### IAM

```text
USER_READ
USER_MANAGE
USER_INVITE
ROLE_READ
ROLE_ASSIGN
```

### Organization

```text
PROJECT_READ
PROJECT_MANAGE
PROJECT_MEMBER_MANAGE
TEAM_READ
TEAM_MANAGE
COST_CENTER_READ
COST_CENTER_MANAGE
PROVIDER_ACCOUNT_READ
PROVIDER_ACCOUNT_MANAGE
```

### Evidence / Import

```text
EVIDENCE_UPLOAD_OWN
EVIDENCE_UPLOAD_PROVIDER
EVIDENCE_READ
EVIDENCE_DOWNLOAD
IMPORT_READ
IMPORT_RETRY
IMPORT_CONFIRM
IMPORT_CANCEL
```

M2 Group 3 行为约定（2026-08-15）：

- Provider Import 工作流是组织级（ORG）作用域；M2 不为 Import 发明
  Project/Team/Cost-Center scope（ImportBatch 对这些维度没有真实归属关系）。
- 请求顺序：认证 → 所需权限（缺失即 403，先于任何资源查找）→ 当前组织内查找
  （有权限但跨组织/不可见 → privacy-preserving 404）。
- Evidence 关联 Imports 子查询需要 `IMPORT_READ`；Evidence 原始字节下载始终
  需要 `EVIDENCE_DOWNLOAD`。
- 前端权限只是 UX 门控；后端授权始终权威。上传按钮与 provider-account 目录
  需要 `EVIDENCE_UPLOAD_PROVIDER` + `PROVIDER_ACCOUNT_READ` 同时具备。

### Cost / Attribution

```text
COST_READ
DUPLICATE_REVIEW
ALLOCATION_READ
ALLOCATION_EDIT
ALLOCATION_CONFIRM
ALLOCATION_RULE_MANAGE
```

M3 Group 1 作用域基线（policy 映射，seed 不变）：

```text
COST_READ              ORG | PROJECT | COST_CENTER
DUPLICATE_REVIEW       ORG | PROJECT | COST_CENTER
ALLOCATION_READ        ORG | PROJECT | COST_CENTER
ALLOCATION_EDIT        ORG | PROJECT | COST_CENTER
ALLOCATION_CONFIRM     ORG | PROJECT | COST_CENTER
ALLOCATION_RULE_MANAGE ORG
```

SYSTEM_ADMIN seed 不含 finance 权限 → 不自动获得；`/auth/me` 投影随 policy
自动包含 ORG 级 finance grant。Confirm（`IMPORT_CONFIRM`）沿用 M2 的
ORG 级策略，零 seed 改动。

M3 Group 2 Duplicate API 实际只使用 ORG 级 scope：

```text
POST /api/v1/duplicate-candidates/scan                       DUPLICATE_REVIEW @ ORG
GET  /api/v1/duplicate-candidates                            DUPLICATE_REVIEW @ ORG
GET  /api/v1/duplicate-candidates/{candidateId}              DUPLICATE_REVIEW @ ORG
POST /api/v1/duplicate-candidates/{candidateId}/keep         DUPLICATE_REVIEW @ ORG
POST /api/v1/duplicate-candidates/{candidateId}/exclude      DUPLICATE_REVIEW @ ORG
```

M3 Group 3（#49/#50/#51）Cost / Allocation HTTP workflow 同样全部 ORG 级
boundary（pre-allocation Charge 尚无可靠 ownership；PROJECT/COST_CENTER 级
grant 的 applicability 保留在 policy，未来可加 scoped projection）：

```text
GET  /api/v1/costs/charges                                  COST_READ @ ORG
GET  /api/v1/costs/charges/{chargeFactId}                   COST_READ @ ORG
GET  /api/v1/costs/charges/{chargeFactId}/allocation-decisions   ALLOCATION_READ @ ORG
GET  /api/v1/allocation-decisions/{decisionId}              ALLOCATION_READ @ ORG
POST /api/v1/costs/charges/{chargeFactId}/allocation-decisions/manual   ALLOCATION_EDIT @ ORG
PUT  /api/v1/allocation-decisions/{decisionId}/lines        ALLOCATION_EDIT @ ORG
POST /api/v1/costs/charges/{chargeFactId}/allocation-proposal   ALLOCATION_EDIT @ ORG
POST /api/v1/allocation-decisions/{decisionId}/confirm      ALLOCATION_CONFIRM @ ORG
GET  /api/v1/allocation-rules                               ALLOCATION_RULE_MANAGE @ ORG
GET  /api/v1/allocation-rules/{ruleId}                      ALLOCATION_RULE_MANAGE @ ORG
POST /api/v1/allocation-rules/{ruleKey}/versions            ALLOCATION_RULE_MANAGE @ ORG
POST /api/v1/allocation-rules/{ruleId}/archive              ALLOCATION_RULE_MANAGE @ ORG
GET  /api/v1/allocation-targets                             ALLOCATION_EDIT @ ORG
```

统一 enforcement：缺 permission → 403（resource lookup 之前）；有 permission
但跨 org / 不可见资源 → privacy-preserving 404。前端只做 UX visibility /
disable，backend 永远 authoritative。

原因：pre-allocation Charge 尚无可靠 Project/CostCenter ownership，candidate
是跨 confirmed imports 的 org-level review 资源；PROJECT/COST_CENTER 级
finance grant 的 applicability 保留在 policy，未来 allocation 之后可加 scoped
projection。Allocation 权限的 HTTP workflow 属 #49/#50，Group 2 未提供
allocation 相关 API。

### Expense

```text
EXPENSE_CREATE_OWN
EXPENSE_READ_OWN
EXPENSE_SUBMIT_OWN
EXPENSE_REVIEW
EXPENSE_POST
```

### Budget

```text
BUDGET_READ
BUDGET_MANAGE
COMMITMENT_REQUEST
COMMITMENT_APPROVE
COMMITMENT_RELEASE
```

### Ledger

```text
LEDGER_READ
LEDGER_POST
LEDGER_CORRECT
```

### Reconciliation

```text
RECONCILIATION_READ
RECONCILIATION_RUN
RECONCILIATION_RESOLVE
```

### Period

```text
PERIOD_READ
PERIOD_CLOSE
PERIOD_REOPEN
```

### Audit

```text
AUDIT_READ
```

## 4. 默认 Role Matrix

| 能力 | Employee | Project Owner | Finance Reviewer | Finance Admin | System Admin |
|---|---:|---:|---:|---:|---:|
| 自己的 Expense | ✓ | ✓ | ✓ | ✓ | 可选 |
| Project Cost Read | Own/Assigned | Scoped ✓ | Finance Scope | All | 可选 |
| Provider Evidence Upload |  |  | ✓ | ✓ |  |
| Import Confirm |  |  | ✓ | ✓ |  |
| Duplicate Review |  | Scoped Comment | ✓ | ✓ |  |
| Allocation Edit | Own Claim | Scoped | ✓ | ✓ |  |
| Allocation Confirm |  | 按 Policy | ✓ | ✓ |  |
| Ledger Post |  |  | ✓ | ✓ |  |
| Ledger Correction |  |  | Conditional | ✓ |  |
| Budget Read | Own Visibility | Scoped ✓ | ✓ | ✓ | 可选 |
| Budget Manage |  |  |  | ✓ |  |
| Commitment Request | 按 Policy | ✓ | ✓ | ✓ |  |
| Commitment Approve |  | 按 Scope | ✓ | ✓ |  |
| Reconciliation |  |  | ✓ | ✓ |  |
| Period Close |  |  |  | ✓ |  |
| Period Reopen |  |  |  | Explicit ✓ |  |
| IAM Admin |  |  |  |  | ✓ |

这是 V1 产品策略，不是外部行业标准。

## 5. Data Scope

```text
ORG
PROJECT
TEAM
COST_CENTER
```

例如：

```text
FINANCE_REVIEWER + ORG
PROJECT_OWNER + PROJECT:42
FINANCE_REVIEWER + COST_CENTER:9
```

Server 通过：

```text
RoleAssignment
+
Membership
+
Resource Ownership
```

计算真实授权范围。

## 6. Own Resource

Employee 只能访问：

```text
ExpenseClaim.claimant_member_id = current_member
```

拥有自己的 Expense，不等于可以查看其他员工的原始 Evidence。

## 7. Project Scope

React 可以 Hide/Disable 按钮，但这只是 UX。

真正安全边界必须在 Backend Query / Application Service。

错误做法：

```text
前端不显示 Project 99
```

正确做法：

```text
后端只查询 Authorized Project IDs
```

## 8. Finance Scope

V1 支持：

```text
ORG-wide
COST_CENTER scoped
```

Finance Reviewer 的 Charge/Ledger 查询必须应用 Scope。

Finance Admin 默认 ORG-wide。

## 9. Sensitive Action

以下动作不能只依赖长 TTL Permission Cache：

```text
LEDGER_POST
LEDGER_CORRECT
BUDGET_MANAGE
PERIOD_CLOSE
PERIOD_REOPEN
ROLE_ASSIGN
USER_MANAGE
```

需要 Security Version / Fresh Validation。

## 10. Raw Evidence

Provider 文件可能含：

```text
User ID
API Key Name
Billing Data
敏感 Identifier
```

所以：

```text
EVIDENCE_DOWNLOAD
```

权限必须比普通 Normalized Cost Read 更严格。

Project Owner 通常看到项目相关 Normalized Fact，不直接下载全组织原始账单。

## 11. 必须 Audit 的动作

```text
User Disable
Role Assign / Revoke
Import Confirm
Duplicate Exclude
Allocation Confirm
Expense Approve / Reject
Budget Total Change
Commitment Activate / Release
Ledger Post
Correction
Reconciliation Resolve
Period Close / Reopen
```

## 12. 授权测试

每个 Sensitive Endpoint 至少测试：

```text
正确 Role + Scope → Success
错误 Role → 403
正确 Role + 错误 Scope → 403 / Privacy-preserving 404
Disabled User → Reject
Role Change 后 Stale Cache 不可继续敏感操作
```
