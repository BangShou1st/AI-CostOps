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

> M2 Group 1 实际实现（V4 migration）。M2 结束于 `ImportBatch.PARSED / FAILED`；
> canonical cost facts（`external_document` / `consumption_fact` / `pricing_fact` /
> `charge_fact` / `attribution_hint`）属于 M3，本组不创建。

### `evidence`

不可变文件身份 + 存储生命周期：

```text
id
org_id
sha256 CHAR(64)
object_key
original_filename
media_type NULL
size_bytes
uploaded_by_member_id
storage_status        STAGING | AVAILABLE | FAILED
storage_error_code    NULL
created_at
updated_at
```

约束：

```text
UQ(org_id, sha256)
FK org_id -> organization
FK uploaded_by_member_id -> organization_member
```

同一组织内同一字节文件重复上传复用 Evidence Identity；跨组织不做物理去重。
`sha256` / `object_key` / `size_bytes` 在对象被接受后不可变。对象键为确定性
`org/{orgId}/evidence/sha256/{sha-prefix}/{sha256}`，禁止文件名/email/token/secret 进入键。
`AVAILABLE` 不允许被晚到的失败降级；`STAGING` 且对象已存在（大小 + 显式 SHA-256
metadata 匹配）时可修复为 `AVAILABLE`。ETag 不是 Evidence checksum 契约。

### `import_batch`

```text
id
org_id
evidence_id
provider_account_id        M2 必须指向当前组织 ACTIVE Provider Account
expected_provider_code
source_type                FILE_EXPORT | USAGE_API_JSON | COSTS_API_JSON
parser_version
status                     PENDING | PROCESSING | PARSED | FAILED | CANCELED
period_start NULL
period_end NULL
created_by_member_id
created_at
updated_at
```

约束：

```text
UQ(evidence_id, provider_account_id, source_type, parser_version)
FK org_id -> organization
FK evidence_id -> evidence
FK provider_account_id -> provider_account
FK created_by_member_id -> organization_member
```

Batch identity 是 Evidence + Provider Account + source type + parser version。
复用同一 Batch 不隐式创建新 Attempt；重试/恢复显式创建新 Attempt。
`PARSED` 有意不命名为 `READY_FOR_REVIEW`（后者是 M3 语义）。
Provider code 在 Batch 上快照为 `expected_provider_code`，避免后续 Provider Account
元数据变更改写历史导入上下文。

### `import_attempt`

既是执行历史，也是 DB-backed Job（不可变 lineage）：

```text
id
import_batch_id
attempt_no
status                 QUEUED | RUNNING | SUCCEEDED | FAILED | CANCELED
trigger_type           INITIAL | LEASE_RECOVERY | MANUAL_RETRY
predecessor_attempt_id NULL
available_at
lease_owner NULL
lease_until NULL
lease_version
parser_version
detected_provider_code NULL
schema_fingerprint CHAR(64) NULL
started_at NULL
finished_at NULL
error_code NULL
error_summary NULL
records_seen
records_valid
warning_count
error_count
created_at
```

约束与索引：

```text
UQ(import_batch_id, attempt_no)
IDX(status, available_at, id)                  idx_import_attempt_queue
IDX(status, lease_until, id)                   idx_import_attempt_lease
IDX(import_batch_id, status, id)               idx_import_attempt_batch_status
FK import_batch_id -> import_batch
FK predecessor_attempt_id -> import_attempt (self)
```

租约语义：

```text
lease duration 默认 60s（DB 时钟 UTC_TIMESTAMP(6)）
heartbeat 默认 20s
自动恢复预算默认 3 个 LEASE_RECOVERY Attempt
```

claim 是短事务：`FOR UPDATE SKIP LOCKED` 选取 QUEUED Attempt → 置 RUNNING +
lease + `lease_version+1` → Batch PROCESSING。每次 claim 递增 `lease_version`
实现 fencing。expired RUNNING Attempt 被恢复为 FAILED(WORKER_LEASE_EXPIRED) 并
创建 successor（LEASE_RECOVERY）；恢复预算耗尽后 Batch FAILED，等待人工重试。
stale owner/version 不能 heartbeat、persist、finalize。

### `raw_provider_record`

```text
id
import_attempt_id
record_index
record_locator         例如 cost.csv:row=12
provider_record_key NULL
raw_payload JSON       已脱敏，Evidence 对象才是权威原始字节
normalized_payload JSON NULL
usage_start NULL
usage_end NULL
normalize_status       NORMALIZED | WARN | ERROR
created_at
```

约束：

```text
UQ(import_attempt_id, record_index)
FK import_attempt_id -> import_attempt
```

持久化是有界批次（默认 500 条/事务），每个事务先锁并验证 Attempt 所有权
`(attempt_id, lease_owner, lease_version, lease 未过期)`。失败 Attempt 的
RawRecord/Issue 永久保留 lineage。

### `import_issue`

```text
id
import_attempt_id
raw_provider_record_id NULL
severity                WARN | ERROR
issue_code
record_locator NULL
field_name NULL
message
raw_value_masked NULL
created_at
```

约束：

```text
FK import_attempt_id -> import_attempt
FK raw_provider_record_id -> raw_provider_record
```

WARN 可继续且 Attempt 仍可 SUCCEEDED；存在 ERROR 则 Attempt FAILED、Batch FAILED。
禁止把 Secret Copy 到 Issue（`raw_value_masked` 只允许掩码值）。

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
