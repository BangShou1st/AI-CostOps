# 08. Provider Import 详细设计

## 1. Adapter 的职责

Provider Adapter 把 Provider-native Evidence 转成：

```text
RawProviderRecord
+
Canonical Facts
+
Attribution Hints
```

它**不能**：

```text
Posting Ledger
决定企业最终 Allocation
猜缺失 Price
静默猜未知字段
```

## 2. Adapter Contract

概念接口：

```text
ProviderAdapter
  providerCode()
  parserVersion()
  inspect(EvidenceSource)
  parse(EvidenceSource, RawRecordSink)
  normalize(RawProviderRecord)
```

`inspect` 返回：

```text
Detected Provider
Schema Fingerprint
Files / Sheets
Recognized Columns
Missing Required Columns
Unknown Columns
Warnings
```

`normalize` 可以产出：

```text
ExternalDocument
ConsumptionFact
PricingFact
ChargeFact
AttributionHint
```

## 3. Schema Fingerprint

将：

```text
Archive Entries
Sheet Names
Normalized Headers
```

规范化后做 SHA-256。

Fingerprint 用于发现 Schema Drift，不等于“语义完全兼容”。

## 4. Pipeline

```text
Evidence
→ Inspect
→ Parse
→ Persist Raw Records
→ Validate
→ Normalize
→ Attribution Hints
→ Duplicate / Overlap Analysis
→ Allocation Proposal
→ READY_FOR_REVIEW
```

大文件使用 Streaming + Bounded Batch，禁止全量一次性加载内存。

## 5. 文件安全

建议开发默认：

```text
Max Upload: 100 MiB
Max Expanded ZIP: 500 MiB
Max ZIP Entries: 100
```

这些是工程默认配置，不是 Provider 事实。

ZIP 必须防：

```text
Path Traversal
Abnormal Expansion
Unsupported Nested Archive
```

## 6. DeepSeek

观察到 ZIP：

```text
cost-*.csv
amount-*.csv
```

`cost` Header：

```text
user_id
start_time_iso
end_time_iso
model
wallet_type
cost
currency
```

`amount` Header：

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

### cost.csv

创建：

```text
ExternalDocument(COST_EXPORT)
ChargeFact
```

映射：

```text
cost → amount
currency → currency
```

`wallet_type` 保持 Provider-native Metadata，不猜 Enum。

### amount.csv

只在语义有证据时创建 Consumption/Pricing Candidate。

禁止假设：

```text
amount * price = cost
```

API Key 只保存 Hash / Masked Hint。

## 7. Kimi / Moonshot

观察到：

```text
账单汇总
```

列：

```text
时间范围
用户ID
组织ID
客户主体
充值账户消耗（元）
赠送账户消耗（元）
```

V1：

```text
ChargeFact.amount
→ 充值账户消耗（元）
```

因为这是观察到的现金/充值账户消费字段。

```text
赠送账户消耗（元）
```

保留为 Provider-native Promotional Metric，不默认作为额外正向现金成本重复 Posting。

不把赠金强行映射为 FOCUS Credit。

Provider Org/User 只是 Attribution Hint。

## 8. GLM / Zhipu

观察到：

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

V1：

```text
ChargeFact.amount
→ 总消费金额
```

其他字段独立保存为 Settlement Metadata：

```text
目录总价
信用支付
赠金抵扣
应付
已付款
待付款
结算状态
```

禁止自行推导字段公式。

如果：

```text
总消费金额 = 0
但 已付款 != 0
```

也不自行替换 Charge Amount，而是交给 Reconciliation / Review。

## 9. MiMo

观察到 `Model usage detail`：

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
Request Count
```

ConsumptionFacts：

```text
INPUT_HIT_TOKENS
INPUT_MISS_TOKENS
OUTPUT_TOKENS
REQUEST_COUNT
```

`Total Tokens` 可以作为 Total Meter 保留，但报表不能把：

```text
Total Tokens + 各 Component
```

再相加。

Postable Charge：

```text
Consumed Amount
```

Hit/Miss/Output Amount 在 V1 作为分析/Provider Metadata 保留，不创建会 Double Count 的多条 Posting Charge。

`Plugin usage detail` 即使为空也是合法 Sheet。

## 10. OpenAI

本地观察到的 CSV 是 Empty Export，只能证明：

```text
start_time
end_time
start_time_iso
end_time_iso
```

不能声称这是完整非空 CSV Schema。

所以 Fixture 分两类。

### A. Observed CSV Fixture

用于：

```text
Recognize
Parse Empty Bucket
Persist Raw
No Invented Cost
```

### B. Official API JSON Fixture

基于官方 Organization Usage / Costs API。

Usage 可能包含：

```text
project_id
user_id
api_key_id
model
batch / service tier
input / output / cached token
request count
```

Costs 可能包含：

```text
amount.value
amount.currency
line_item
project_id
api_key_id
quantity
```

Populated Synthetic Test 基于这个官方 JSON Contract，而不是捏造 CSV Header。

## 11. Validation Level

### ERROR

```text
MISSING_REQUIRED_COLUMN
MALFORMED_ARCHIVE
UNSUPPORTED_SCHEMA
INVALID_REQUIRED_TYPE
UNREADABLE_WORKBOOK
```

### WARN

```text
UNKNOWN_COLUMN
EMPTY_OPTIONAL_SHEET
UNKNOWN_PROVIDER_ENUM
MISSING_OPTIONAL_DIMENSION
```

Unknown Field 保留在 Raw Payload。

## 12. Duplicate / Overlap

完全相同字节：

```text
Evidence SHA-256
```

不同 Export 之间只能基于：

```text
Provider
Provider Account
Period
Stable External Key（如果有）
Dimensions
Amount / Currency
```

生成：

```text
SUSPECTED_DUPLICATE
```

Heuristic Fingerprint 不是 Universal Unique Constraint。

最终由 Reviewer 判：

```text
UNIQUE
EXCLUDED_DUPLICATE
```

## 13. Fixture 分类

每个 Adapter 至少准备：

```text
Observed/Sanitized Header Fixture
Empty Fixture（如果真实观察到）
Synthetic Populated Fixture
Schema Drift Fixture
Missing Required Fixture
Unknown Column Fixture
```

标注：

```text
REAL_SCHEMA_SANITIZED
OFFICIAL_SCHEMA_SYNTHETIC
SYNTHETIC_ENTERPRISE
```

真实原始账户文件不进入 Public Repo。

## 14. Parser Version

例如：

```text
mimo-model-usage-v1
deepseek-usage-zip-v1
```

如果 Normalize 行为改变 Canonical Output：

```text
Parser Version++
```

同 Evidence 重新处理时创建新 ImportBatch。

## 15. Performance

CSV/ZIP 使用 Streaming。

XLSX 尽量使用 Streaming/SAX Reader。

Batch Size 通过 Benchmark 决定。

目标：

```text
500k Normalized Facts 不 OOM
```

只有实测后才写 Throughput。
