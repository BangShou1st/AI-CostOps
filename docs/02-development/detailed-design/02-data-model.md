# 02. 数据模型设计

## 1. MySQL 全局约定

```text
MySQL 8.4 LTS
InnoDB
utf8mb4
Flyway
UTC
```

### Primary Key

```sql
id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY
```

V1 不引入 Snowflake/TSID。

### API ID

数据库：

```text
BIGINT
```

JSON：

```json
{"id":"123456789012345678"}
```

### Money

```sql
DECIMAL(20,8)
```

Java：

```text
BigDecimal
```

API：

```json
{"amount":"1.53512800","currency":"CNY"}
```

### Usage Quantity

```sql
DECIMAL(30,8)
```

### Currency

```text
CHAR(3)
```

不默认 CNY/USD。

### Time

Instant：

```text
DATETIME(6)
```

应用统一写 UTC。

BillingPeriod：

```text
[start_date, end_date)
```

例如 8 月：

```text
2026-08-01 <= date < 2026-09-01
```

### Status

优先：

```text
VARCHAR(32)
+ Application Enum
+ 必要 CHECK
```

不把 MySQL `ENUM` 作为主要领域状态机制。

### 删除策略

Financial History 不做 Soft Delete。

Master Data 使用：

```text
ACTIVE
ARCHIVED
DISABLED
```

## 2. IAM / Organization 表

### `organization`

```text
id
name
slug
status
settings_json
created_at
updated_at
```

约束：

```text
UQ(slug)
```

V1 应用只暴露一个 Active Organization，但业务表仍保留 `org_id`。

### `app_user`

```text
id
email_normalized
display_name
status
security_version
created_at
updated_at
```

约束：

```text
UQ(email_normalized)
IDX(status)
```

### `user_credential`

```text
user_id PK/FK
password_hash
password_changed_at
updated_at
```

绝不保存明文 Password / Reset Token。

### `organization_member`

```text
id
org_id
user_id
employee_no NULL
default_cost_center_id NULL
status
joined_at
```

约束：

```text
UQ(org_id,user_id)
IDX(user_id,status)
```

### `role` / `permission`

V1 Seed：

```text
EMPLOYEE
PROJECT_OWNER
FINANCE_REVIEWER
FINANCE_ADMIN
SYSTEM_ADMIN
```

`role_permission` 建立多对多。

### `role_assignment`

```text
id
org_member_id
role_id
scope_type
scope_id
assigned_by
created_at
```

`scope_type`：

```text
ORG
PROJECT
TEAM
COST_CENTER
```

约束：

```text
UQ(org_member_id,role_id,scope_type,scope_id)
```

`scope_id` 由 Application Service 校验真实引用。

### `invitation`

```text
id
org_id
email_normalized
token_hash
initial_role_code
status
expires_at
invited_by
accepted_by_user_id NULL
created_at
accepted_at NULL
```

### `cost_center` / `team` / `project`

都保留：

```text
org_id
code
name
status
```

并使用：

```text
UQ(org_id,code)
```

历史引用对象 Archive，不 Hard Delete。

### `provider_account`

```text
id
org_id
provider_code
display_name
external_account_ref NULL
status
metadata_json
```

V1 Post-billing 不要求保存 Provider API Secret。

## 3. Evidence / Ingestion 表

### `evidence`

不可变文件身份：

```text
id
org_id
sha256
object_key
original_filename
media_type
size_bytes
uploaded_by_member_id
created_at
```

约束：

```text
UQ(org_id,sha256)
```

同一字节文件重复上传复用 Evidence Identity。

### `import_batch`

```text
id
org_id
evidence_id
provider_account_id NULL
expected_provider_code NULL
detected_provider_code NULL
source_type
schema_fingerprint NULL
parser_version
status
confirmed_attempt_id NULL
period_start NULL
period_end NULL
created_by_member_id
created_at
updated_at
```

约束：

```text
UQ(evidence_id,parser_version,source_type)
```

Parser 行为影响 Canonical 输出时必须升级 `parser_version`。

### `import_attempt`

既是执行历史，也是 DB-backed Job：

```text
id
import_batch_id
attempt_no
status
available_at
lease_owner NULL
lease_until NULL
started_at NULL
finished_at NULL
error_code NULL
error_summary NULL
records_seen
records_valid
facts_created
```

约束：

```text
UQ(import_batch_id,attempt_no)
IDX(status,available_at,lease_until)
```

### `raw_provider_record`

```text
id
import_attempt_id
record_index
record_locator
provider_record_key NULL
raw_payload JSON
usage_start NULL
usage_end NULL
normalize_status
created_at
```

`record_locator` 例如：

```text
Model usage detail!row=4
cost.csv:row=12
$.data[10]
```

### `import_issue`

记录可供人工审查的数据问题：

```text
severity
issue_code
field_name
message
raw_value_masked
```

禁止把 Secret Copy 到 Issue。

## 4. Canonical Cost 表

### `external_document`

表示 Provider 外部账单/Usage 文档语义：

```text
document_type
period_start/end
currency
reported_total_amount
reported_payable_amount
reported_paid_amount
reported_outstanding_amount
metadata_json
```

类型：

```text
USAGE_EXPORT
COST_EXPORT
STATEMENT
INVOICE
BILL_SUMMARY
```

### `consumption_fact`

保存：

```text
provider_code
service_code
model
meter_code
quantity
unit
usage_start/end
time_grain
provider_org_ref
provider_project_ref
provider_user_ref
provider_api_key_hash/label
```

唯一：

```text
UQ(raw_record_id,fact_index)
```

### `pricing_fact`

只有证据真的提供 Pricing 时才创建。

禁止通过：

```text
amount / quantity
```

自行反推 Provider Price。

### `charge_fact`

Canonical Monetary Fact：

```text
provider_code
charge_category
amount
currency
funding_source
payable_amount
paid_amount
outstanding_amount
period_start/end
review_status
duplicate_of_charge_id
current_allocation_decision_id
metadata_json
```

`review_status`：

```text
CLEAN
SUSPECTED_DUPLICATE
EXCLUDED_DUPLICATE
EXCLUDED_NONCOST
```

### `attribution_hint`

保存：

```text
PROVIDER_API_KEY
PROVIDER_PROJECT
PROVIDER_USER
EMPLOYEE_SELECTION
```

以及 Candidate Scope / Confidence。

Hint 不是最终 Allocation Truth。

## 5. Attribution 表

### `allocation_rule`

版本化追加：

```text
rule_key
version
priority
status
match_type
match_config_json
target_project_id
target_cost_center_id
target_team_id
effective_from/to
```

历史 Rule Version 不重写。

### `allocation_decision`

Subject：

```text
CHARGE_FACT
EXPENSE_CLAIM
```

Status：

```text
DRAFT
CONFIRMED
SUPERSEDED
```

Source Subject 保存 `current_allocation_decision_id`。

### `allocation_line`

```text
allocated_amount
currency
project_id
cost_center_id
team_id
budget_commitment_id
```

权威不变量：

```text
SUM(lines.amount) = subject amount
```

同一 Currency/Scale。

## 6. Expense / Approval 表

### `expense_claim`

核心：

```text
claimant_member_id
evidence_id
expense_date
amount
currency
status
allocation_decision_id
approval_case_id
version
```

### `approval_case`

Subject：

```text
EXPENSE_CLAIM
BUDGET_COMMITMENT
```

### `approval_action`

Append-only：

```text
SUBMIT
APPROVE
REJECT
REQUEST_INFO
RESUBMIT
CANCEL
```

## 7. BillingPeriod / Budget 表

### `billing_period`

```text
period_start
period_end
status
close_generation
closing_started_at
closed_at
reopened_at
version
```

约束：

```text
UQ(org_id,period_start,period_end)
```

### `budget`

```text
billing_period_id
scope_type
scope_id
currency
total_amount
actual_amount
committed_amount
status
version
```

V1：

```text
available
= total_amount
- actual_amount
- committed_amount
```

`committed_amount` 表示 Outstanding Commitment。

### `budget_commitment`

状态：

```text
REQUESTED
ACTIVE
PARTIALLY_CONSUMED
CONSUMED
RELEASED
REJECTED
CANCELED
```

保留：

```text
requested_amount
approved_amount
remaining_amount
```

### `budget_commitment_usage`

Append-only 记录 Commitment 被哪些 LedgerEntry 消费。

## 8. Ledger 表

### `ledger_posting`

Transaction Header：

```text
posting_key
source_type
source_id
allocation_decision_id
billing_period_id
status
posted_by
posted_at
```

唯一：

```text
UQ(org_id,posting_key)
```

示例：

```text
CHARGE:{chargeId}:ALLOCATION:{allocationId}
EXPENSE:{expenseId}
CORRECTION:{correctionGroupId}
```

### `ledger_entry`

Immutable Line：

```text
entry_type
amount
currency
project_id
cost_center_id
team_id
budget_id
source_charge_fact_id
source_expense_claim_id
allocation_line_id
correction_group_id
reverses_entry_id
```

类型：

```text
COST
CREDIT
ADJUSTMENT
REVERSAL
```

Signed Amount：

```text
positive = cost
negative = credit / reversal / reduction
```

### `correction_group`

记录：

```text
correction_key
reason_code
reason_text
target_posting_id / target_entry_id
status
```

历史 Entry 不 UPDATE。

## 9. Reconciliation / Close 表

### `reconciliation_run`

保存：

```text
billing_period
external_document
scope_level
tolerance_amount
currency
status
summary_json
```

### `reconciliation_case`

Case：

```text
MISSING_INTERNAL
MISSING_EXTERNAL
AMOUNT_MISMATCH
DUPLICATE_CANDIDATE
UNALLOCATED
OTHER
```

Reason：

```text
PROMOTIONAL_CREDIT
REFUND
TAX
FX
MISSING_RECORD
DUPLICATE
ROUNDING
LATE_USAGE
UNKNOWN
```

### `period_close_run`

状态：

```text
CHECKING
BLOCKED
CLOSED
CANCELED
FAILED
```

### `period_close_check`

Check：

```text
OPEN_IMPORTS
UNRESOLVED_DUPLICATES
UNALLOCATED_CHARGES
UNPOSTED_APPROVED_EXPENSES
OPEN_MATERIAL_RECONCILIATION
PENDING_CORRECTIONS
LEDGER_INTEGRITY
```

## 10. Audit / Idempotency

### `audit_event`

Append-only。

禁止记录 Password、Refresh Token、Verification Code、Full API Key、JWT Secret。

### `api_idempotency`

用于没有自然 Business Key 的 Mutation。

唯一：

```text
UQ(org_id,actor_member_id,operation,idempotency_key)
```

同 Key + 同 Request：

```text
返回原结果
```

同 Key + 不同 Request：

```text
409 IDEMPOTENCY_KEY_REUSED
```

存在 MySQL，不存在 Redis。

## 11. 初始 Flyway 顺序

```text
V001__organization_iam.sql
V002__evidence_ingestion.sql
V003__canonical_cost_facts.sql
V004__attribution.sql
V005__expense_approval.sql
V006__billing_period_budget.sql
V007__ledger.sql
V008__reconciliation.sql
V009__audit_idempotency.sql
V010__seed_roles_permissions.sql
```

实际 Bootstrap 时可以因为 M1 Audit 提前而重新编排 Migration 编号，但原则不变：

```text
Forward-only
Clean DB 可完整重建
PR 可审查
```

## 12. Index 原则

初始 Index 只围绕：

```text
Unique
FK
Import Claim
Ledger Query
Budget Lookup
Close Blocker
Reconciliation
```

100k/500k/1m 后通过 EXPLAIN 再优化，不提前堆索引。
