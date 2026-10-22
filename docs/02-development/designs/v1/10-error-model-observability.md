# 10. 错误模型与可观测性

## 1. HTTP Error Format

使用 Spring `ProblemDetail` / `application/problem+json`。

示例：

```json
{
  "type":"https://aicostops.dev/problems/budget-insufficient",
  "title":"Budget commitment cannot be activated",
  "status":409,
  "detail":"Available amount is lower than the requested commitment.",
  "instance":"/api/v1/budget-commitments/88/approve",
  "code":"BUDGET_INSUFFICIENT",
  "traceId":"01J...",
  "currentState":"REQUESTED"
}
```

`type` URI 在 V1 只是稳定 Identifier，不要求真的部署网页。

## 2. Status / Code

### 400

```text
REQUEST_MALFORMED
INVALID_FILTER
INVALID_MONEY_FORMAT
```

### 401

```text
AUTH_INVALID_CREDENTIALS
AUTH_ACCESS_EXPIRED
AUTH_SESSION_EXPIRED
AUTH_REFRESH_REPLAY
```

Login 不暴露 Account 是否存在。

### 403

```text
FORBIDDEN
SCOPE_FORBIDDEN
ACCOUNT_DISABLED
```

### 404

```text
RESOURCE_NOT_FOUND
```

必要时用于避免跨 Scope 泄露 Resource 是否存在。

### 409

```text
STATE_CONFLICT
VERSION_CONFLICT
IDEMPOTENCY_KEY_REUSED
BUDGET_INSUFFICIENT
LEDGER_ALREADY_POSTED
PERIOD_NOT_OPEN
PERIOD_CLOSE_BLOCKED
AUTH_REFRESH_RACE
```

### 422

```text
IMPORT_VALIDATION_FAILED
ALLOCATION_SUM_MISMATCH
DUPLICATE_REVIEW_REQUIRED
MISSING_REQUIRED_EVIDENCE
```

### 429

```text
AUTH_RATE_LIMITED
```

能计算时返回 `Retry-After`。

### 503

```text
REDIS_UNAVAILABLE_FOR_AUTH
OBJECT_STORAGE_UNAVAILABLE
DEPENDENCY_TEMPORARILY_UNAVAILABLE
```

## 3. Domain Exception

Application/Domain 抛 Typed Error：

```text
BudgetInsufficient
PeriodClosed
AllocationMismatch
InvalidStateTransition
DuplicatePosting
```

Controller 统一映射，避免每个 Controller 手写 `try/catch Exception`。

## 4. Validation

示例：

```json
{
  "code":"VALIDATION_FAILED",
  "violations":[
    {
      "field":"amount",
      "message":"must be greater than zero"
    }
  ]
}
```

不暴露 Java Class Name。

## 5. Import Error

用户可审查问题写入：

```text
import_issue
```

例如：

```text
MISSING_REQUIRED_COLUMN
INVALID_AMOUNT
UNKNOWN_COLUMN
UNSUPPORTED_SCHEMA
UNKNOWN_PROVIDER_ENUM
```

Stack Trace 只进内部 Log。

## 6. Structured Log

生产风格字段：

```text
timestamp
level
service
trace_id
request_id
user_id
org_id
route
```

业务上下文：

```text
import_batch_id
import_attempt_id
evidence_id
provider
posting_id
billing_period_id
reconciliation_run_id
```

## 7. Secret Redaction

禁止记录：

```text
Password
Refresh Token
Verification Code
Authorization Header
Full Provider API Key
MinIO Secret
JWT Signing Key
Provider Raw Payload
Prompt / Response Body
```

Parser Error 只记录：

```text
Record Locator
Field
Error Code
```

## 8. Audit vs Log

Log：

```text
运维 / Debug
```

Audit：

```text
业务 / Security Accountability
```

Audit 生命周期不能跟普通日志一起被清掉。

## 9. Trace ID

每个 Request 都有 `traceId`。

返回：

```text
Error Body
Response Header
```

Async ImportAttempt 记录关联 Trace/Job ID。

## 10. Metrics

### HTTP

```text
Request Count
Latency
5xx
```

### Auth

```text
Login Success / Failure
Rate Limited
Refresh Success / Race / Replay
```

### Import

```text
Queued / Running / Success / Failure
Records/sec
Facts Created
Schema Mismatch
```

### Financial

```text
Ledger Posting
Posting Conflict
Budget Activation Conflict
Budget Overrun
Correction
```

### Reconciliation

```text
Run Count
Open Case by Severity
Resolve Duration
```

### Close

```text
Close Attempt
Blocked Check
Duration
Reopen
```

### Redis

```text
Error
Latency
Cache Hit/Miss
```

## 11. Actuator

合理暴露：

```text
health
info
metrics
prometheus（V1.5）
```

Sensitive Endpoint 不匿名开放。

## 12. Dependency Health

MySQL Down：

```text
核心财务 API 不可用
```

Redis Down：

```text
Login/Refresh 按 Auth Failure Policy
Cache 可回源 MySQL
财务 Truth 不变
```

MinIO Down：

```text
Upload/Download 不可用
Ledger Read 可继续
```

## 13. Retry

只 Retry 明确 Transient Failure：

```text
Bounded DB Deadlock Retry
Object Storage Transient Retry
```

不 Retry：

```text
Validation
Budget Insufficient
Period Closed
Wrong Password
```

禁止 Infinite Retry。

## 14. V1.5 Alert 候选

```text
Import Failure Ratio
Redis Error Spike
MySQL Connection Saturation
Material Reconciliation Growth
Close Blocked Too Long
5xx Rate
```

未实测前不声明 Production SLO。
