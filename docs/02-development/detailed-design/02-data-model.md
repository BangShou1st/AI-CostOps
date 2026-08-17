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

> M2 Group 1 实际实现（V4 migration）+ M3 Group 1 扩展（V8 migration）。
> M3 将 canonical cost facts（`external_document` / `consumption_fact` /
> `pricing_fact` / `charge_fact` / `attribution_hint`）建表，并把 normal success
> path 的 Batch 终态推进到 `READY_FOR_REVIEW` → `CONFIRMED`。

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
status                     PENDING | PROCESSING | PARSED | READY_FOR_REVIEW | CONFIRMED | FAILED | CANCELED
period_start NULL
period_end NULL
confirmed_attempt_id NULL   M3：confirm 时指向被确认的 ImportAttempt
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
`PARSED` 是 M2 历史遗留值；M3 normal success path 由同一 finalization
transaction 原子产生 `SUCCEEDED` Attempt + `READY_FOR_REVIEW` Batch，
`CONFIRMED`（含 `confirmed_attempt_id`）是终态且不可 cancel/retry。
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

#### M2 Group 3：手动重试与取消（2026-08-15）

- 手动 Retry 仅对 `FAILED` 或 `CANCELED` 的 Batch 合法：在同一个事务里创建
  `MANUAL_RETRY` successor Attempt（`attempt_no = latest + 1`、
  `predecessor_attempt_id = latest`、`QUEUED`、`lease_owner/until NULL`、
  `lease_version 0`、`parser_version` 沿用 Batch 冻结版本），Batch 回到 `PENDING`。
  旧 Attempt / RawRecord / Issue 永不删除或改写（不可变 lineage）。
- `CANCELED` Batch 可手动重试的原因：Batch identity 唯一（Evidence + Provider
  Account + source type + parser version），复用同一 Batch 保留 lineage，避免为
  已取消 Batch 发明第二个身份。
- Cancel 仅对 `PENDING + QUEUED` 或 `PROCESSING + RUNNING` 合法：Attempt 置
  `CANCELED` + `finished_at`，清除 `lease_owner/lease_until`，保留
  `started_at`、计数器、检测字段与 `lease_version`；Batch 同事务置 `CANCELED`。
  取消不中断 worker 线程，依靠现有 lease/fencing 使 stale worker 的后续写入失败。
- M2 Group 3 未引入任何新表/业务列/状态值；`V7__m2_import_workflow_review_indexes.sql`
  仅为 Evidence / Import / RawRecord / Issue 增加 review 读索引。
- 读取租户边界在 SQL 层强制执行：Import detail/列表查找都带
  `ib.org_id = ?`，Evidence / Provider Account lineage join 附加
  `e.org_id = ib.org_id` / `pa.org_id = ib.org_id`，跨组织 lineage 的异常行
  不可见；Raw detail 用 `recordId + attemptId` scoped read。
- `raw_provider_record.record_locator` / `provider_record_key` 是
  adapter 可控元数据（例如 GLM 任意 worksheet 名生成的 locator）：持久化边界
  与读取边界都跑 `SecretShapes` redaction（`[REDACTED]`）并截断到
  VARCHAR(500)，safe provider identity（如 `keyid_fake`）保持不变。

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
CHECK usage window（V6）：usage_start IS NULL OR usage_end IS NULL
  OR usage_start <= usage_end
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

> 本 PR 实施契约（V8 migration）：五表共用
> `id / org_id / raw_record_id / fact_index / created_at` +
> `UQ(raw_record_id, fact_index)` + `CHECK (fact_index >= 0)` +
> FK org/raw + `idx_<t>_org_created`。金额列 DECIMAL(20,8)、用量 DECIMAL(30,8)、
> currency CHAR(3)；Java 侧 BigDecimal 全程，禁止 float/double 权威金额，
> 禁止依赖 MySQL silent rounding（精确表示 guard，见 08-provider-import-design）。

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

V9 起 `duplicate_of_charge_id` 是 same-org composite self-FK：
`(duplicate_of_charge_id, org_id) → charge_fact(id, org_id)`，跨 org duplicate
pointer 被 DB 拒绝（`UNIQUE(id, org_id)` 支撑）；self/chain guard 由应用层
Exclude 事务负责（MySQL 8.4 不允许 CHECK 引用 auto-increment 列）。
`current_allocation_decision_id` 见 `allocation_decision` 的 composite FK。

### `duplicate_candidate`（V9）

两条 Charge 之间的候选重复关系，一条 Charge 可同时挂多个 candidate：

```text
charge_fact_id / matched_charge_id   CHECK(charge_fact_id < matched_charge_id)
candidate_type                       EXACT | OVERLAP
fingerprint                          SHA-256 evidence marker（不建 UNIQUE）
algorithm_version                    属于 pair identity（UNIQUE(org,pair,version)）
match_reason                         human-readable evidence 说明
status                               OPEN | KEPT_CLEAN | CONFIRMED_DUPLICATE | SUPERSEDED
resolved_at                          非 OPEN 必填
```

两端都是 same-org composite FK。EXACT/OVERLAP 的 evidence signature 只用
canonical 列（org / provider account lineage / provider / category / currency /
half-open period window / amount），不回读 raw payload。fingerprint 是可解释的
deterministic marker，不是 row identity；重跑同 algorithm 对 terminal pair 是
`INSERT ... duplicate → no-op`，不重开历史。

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

## 5. Attribution 表（V9 foundation）

### `allocation_rule`

版本化追加，`UNIQUE(org_id, rule_key, version)`，历史 Rule Version 不重写，
repository 不提供 definition UPDATE：

```text
rule_key
version                CHECK(version > 0)
priority               CHECK(1..9999)，无 UNIQUE(org,priority)——历史版本可复用 priority
status                 ACTIVE | ARCHIVED（lifecycle metadata）
match_hint_type        PROVIDER_API_KEY | PROVIDER_PROJECT | PROVIDER_USER
match_value            exact deterministic match
provider_code / provider_account_id(NULL=任意 account)
target_project_id / target_cost_center_id / target_team_id   CHECK 恰一个非 NULL
effective_from/to      half-open [from,to)，CHECK(to IS NULL OR from<to)
```

V1 matcher 只用显式 provider/hint/value 列，对齐 Group 1 attribution_hint 的
evidence types。没有 `match_config_json`，没有 DIMENSION matcher，没有 regex /
expression / generic JSON DSL。同 key 的 ACTIVE version 不允许 overlap 由
`existsActiveOverlapSameKey` 查询（half-open 语义，adjacent 合法）。#50 已实现
create-version 事务（`organization FOR UPDATE` 串行化 `maxVersion + 1`，定义
列永不 UPDATE，新定义 = 新 version）与 archive（ACTIVE → ARCHIVED，生命周期
命令，不改定义；ARCHIVED 永不参与 evaluator，历史 decision 的 trace 保留）。
evaluator tie-break 冻结为 lower priority number → rule_key ASC → version DESC
→ id ASC（SQL ORDER BY 与 Java comparator 一致）。

### `allocation_decision`

Subject：

```text
CHARGE_FACT             charge_fact_id NOT NULL，expense_claim_id NULL
EXPENSE_CLAIM           expense_claim_id NOT NULL，charge_fact_id NULL
```

`expense_claim_id` 在 M3 有 identity、无 FK——M4 建 Expense 表时再补 FK。

Source / trace：

```text
decision_source         MANUAL ⇒ allocation_rule_id NULL
                        RULE   ⇒ allocation_rule_id NOT NULL（指向 immutable rule version）
status                  DRAFT | CONFIRMED | SUPERSEDED
created_by_member_id    NULLable（rule 生成可能无人 creator）
```

One-confirmed-per-charge 由 STORED generated column 保证：

```text
confirmed_charge_fact_id = CASE WHEN status='CONFIRMED' THEN charge_fact_id END
UNIQUE(confirmed_charge_fact_id)
```

Source Subject 保存 `current_allocation_decision_id`——V9 用 composite FK
`(current_allocation_decision_id, id, org_id) → allocation_decision(id,
charge_fact_id, org_id)`，DB 强制 pointer 只能指向同一 Charge、同一 org 的
decision；CONFIRMED 与 pointer mutation 由 #49 Confirm 事务在 charge 锁内
原子完成（decision status + pointer + audit 同事务），competing confirm 由
`UNIQUE(confirmed_charge_fact_id)` 兜底。RULE decision 的 `allocation_rule_id`
指向不可变 rule version，即使该 version 之后被 ARCHIVED，trace 不消失。

### `allocation_line`

```text
line_index              CHECK(>=0)，UNIQUE(decision_id, line_index)
allocated_amount        DECIMAL(20,8)
currency                CHAR(3)
project_id / cost_center_id / team_id   CHECK 恰一个非 NULL
(decision_id, org_id)   composite FK → allocation_decision(id, org_id)
```

权威不变量：

```text
SUM(lines.amount) = subject amount
```

同一 Currency/Scale。Java 侧在 mapper write 前用 `AllocationDecimal.money`
做 exact-representability guard（scale≤8 精确、precision≤20，拒绝任何
rounding/truncation）；`SUM == subject amount` 与 currency equality 由 #49
Confirm 事务验证，schema 不做无法独立保证的 CHECK。

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
