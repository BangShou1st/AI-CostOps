# 03. DTO 与 Schema 约定

> 精确机器类型看 `openapi.yaml`；本文解释关键业务字段。

## Login

Request：

```json
{
  "email":"dev@example.com",
  "password":"********"
}
```

Response：

```json
{
  "accessToken":"...",
  "expiresIn":900,
  "user":{
    "id":"1",
    "displayName":"Developer"
  }
}
```

Refresh Token 只通过 HttpOnly Cookie。

## Evidence Upload

Response：

```json
{
  "evidenceId":"100",
  "deduplicated":false,
  "sha256":"...",
  "importBatchId":"200"
}
```

`deduplicated=true` 表示字节内容已有 Evidence Identity，不表示业务上一定是 Duplicate Charge。

## ImportBatch Summary

```json
{
  "id":"200",
  "status":"READY_FOR_REVIEW",
  "expectedProviderCode":"MIMO",
  "detectedProviderCode":"MIMO",
  "parserVersion":"mimo-model-usage-v1",
  "recordsSeen":3,
  "recordsValid":3,
  "factsCreated":15
}
```

## ChargeFact

```json
{
  "id":"301",
  "providerCode":"MIMO",
  "chargeCategory":"USAGE",
  "amount":"1.53512800",
  "currency":"CNY",
  "periodStart":"2026-08-10",
  "periodEnd":"2026-08-11",
  "reviewStatus":"CLEAN",
  "currentAllocationDecisionId":"500"
}
```

Provider-specific Raw Field 不扩散成公共 DTO 顶级字段；放 Detail Metadata 或 RawRecord Endpoint。

## Allocation

Request：

```json
{
  "subjectType":"CHARGE_FACT",
  "subjectId":"301",
  "reason":"API key belongs to project A",
  "lines":[
    {
      "amount":"1.53512800",
      "currency":"CNY",
      "projectId":"10",
      "costCenterId":"20",
      "budgetCommitmentId":null
    }
  ]
}
```

Confirm 前：

```text
SUM(lines.amount) == source.amount
currency 相同
```

## ExpenseClaim

```json
{
  "id":"800",
  "claimantMemberId":"9",
  "expenseDate":"2026-08-12",
  "amount":"120.00000000",
  "currency":"CNY",
  "description":"AI coding tool monthly subscription",
  "status":"DRAFT",
  "version":1
}
```

## Budget

```json
{
  "id":"1",
  "billingPeriodId":"202608",
  "scopeType":"PROJECT",
  "scopeId":"10",
  "currency":"CNY",
  "totalAmount":"10000.00000000",
  "actualAmount":"3200.00000000",
  "committedAmount":"4000.00000000",
  "availableAmount":"2800.00000000",
  "overBudget":false,
  "version":5
}
```

`availableAmount` 必须由 Server 按：

```text
total - actual - committed
```

计算，Frontend 不自行重算并作为业务 Truth。

## BudgetCommitment Create

```json
{
  "budgetId":"1",
  "description":"August coding-agent allowance",
  "requestedAmount":"1000.00000000",
  "currency":"CNY"
}
```

Header：

```http
Idempotency-Key: <uuid>
```

## Ledger Entry

```json
{
  "id":"900",
  "postingId":"890",
  "entryType":"COST",
  "amount":"1.53512800",
  "currency":"CNY",
  "projectId":"10",
  "costCenterId":"20",
  "sourceChargeFactId":"301",
  "sourceExpenseClaimId":null,
  "reversesEntryId":null,
  "createdAt":"2026-08-12T04:20:30.123456Z"
}
```

## Ledger Lineage

```json
{
  "entryId":"900",
  "posting":{
    "id":"890",
    "postingKey":"CHARGE:301:ALLOCATION:500"
  },
  "allocationDecisionId":"500",
  "source":{
    "type":"CHARGE_FACT",
    "id":"301"
  },
  "rawRecordId":"123",
  "importAttemptId":"88",
  "evidenceId":"77"
}
```

核心要求：

> 从 LedgerEntry 一定能追到 Evidence。

## Reconciliation Run

Request：

```json
{
  "externalDocumentId":"700",
  "billingPeriodId":"8",
  "scopeLevel":"DOCUMENT",
  "toleranceAmount":"0.01000000",
  "currency":"CNY"
}
```

Tolerance 是 Run 的审计输入，不使用隐藏全局默认覆盖历史。

## Reconciliation Case

```json
{
  "id":"1200",
  "caseType":"AMOUNT_MISMATCH",
  "severity":"MATERIAL",
  "status":"OPEN",
  "externalAmount":"100.00000000",
  "internalAmount":"99.50000000",
  "differenceAmount":"0.50000000",
  "currency":"CNY",
  "reasonCode":null
}
```

## BillingPeriod

```json
{
  "id":"8",
  "label":"2026-08",
  "startDate":"2026-08-01",
  "endDate":"2026-09-01",
  "status":"OPEN",
  "closeGeneration":0,
  "version":1
}
```

## Enum 与前端显示

API：

```text
READY_FOR_REVIEW
SUSPECTED_DUPLICATE
FINANCE_REVIEWER
```

Frontend：

```text
待复核
疑似重复
财务复核员
```

显示文案不进入后端 Enum Contract。
