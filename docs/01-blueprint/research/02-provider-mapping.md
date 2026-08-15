# 02. Provider → Canonical Concept Mapping

> 状态：V0.1
> 原则：只映射有证据支持的语义。不能确定时保留 Provider 原始字段，不强行统一。

## 1. Canonical Concept 分层

```text
Evidence
  ↓
RawProviderRecord
  ├─ ConsumptionFact
  ├─ PricingFact (optional)
  └─ ChargeFact
          ↓
    AttributionHint
          ↓
      Allocation
          ↓
      LedgerEntry
```

其中：

- **Consumption**：实际用了什么资源；
- **Pricing**：Provider 用什么数量/单位/单价进行定价；
- **Charge**：Provider 报告了什么费用/抵扣/付款事实；
- **Allocation**：企业自己决定费用内部归属；
- **Ledger**：企业正式确认后的内部账。

---

## 2. Fact Grain

Provider 的数据粒度必须显式保存。

候选枚举：

```text
REQUEST
MINUTE
HOUR
DAY
MODEL
API_KEY
USER
PROJECT
ACCOUNT
ORGANIZATION
BILLING_PERIOD
INVOICE_LINE
UNKNOWN
```

一个 Fact 可以同时有多个维度，例如：

```text
DAY + MODEL + API_KEY
```

实现时可以用：

- `time_grain`
- `dimension_grain`
- nullable dimensions

而不是造无限组合枚举。

---

## 3. DeepSeek

### 原始 Evidence

#### amount.csv

| 原始字段 | Canonical 候选 | 置信度 | 说明 |
|---|---|---:|---|
| `user_id` | ProviderIdentity.user | High | Provider 侧身份 |
| `start_time_iso` | usage_period.start | High | 时间边界 |
| `end_time_iso` | usage_period.end | High | 时间边界 |
| `model` | service/model | High | Provider model |
| `api_key_name` | attribution_hint.api_key_name | High | 仅为 Hint |
| `api_key` | attribution_hint.api_key | High | 仅为 Hint |
| `type` | meter/pricing type | Medium | 无非空样本，不能预设枚举 |
| `price` | observed unit price | Medium | 不能假设单位 |
| `amount` | quantity or amount | Medium | 官方称 usage detail；真实单位需样本验证 |

#### cost.csv

| 原始字段 | Canonical 候选 | 置信度 | 说明 |
|---|---|---:|---|
| `user_id` | ProviderIdentity.user | High | |
| `start_time_iso/end_time_iso` | charge period | High | |
| `model` | service/model | High | |
| `wallet_type` | funding/wallet hint | Medium | **不猜具体枚举** |
| `cost` | provider reported monetary cost | High | |
| `currency` | billing currency | High | |

### Adapter 规则

- 允许 `amount` 记录和 `cost` 记录无法一一匹配；
- 不从 `wallet_type` 名字推断现金/赠送，除非见到真实值或官方定义；
- 原始记录必须保留。

---

## 4. Kimi / Moonshot

### 原始汇总账

| 原始字段 | Canonical 候选 | 置信度 | 说明 |
|---|---|---:|---|
| `时间范围` | statement period | High | |
| `用户ID` | provider user | High | |
| `组织ID` | provider organization | High | |
| `客户主体` | billing entity hint | Medium | 当前样本为空 |
| `充值账户消耗（元）` | paid-balance consumption component | Medium-High | 不直接等价 FOCUS BilledCost |
| `赠送账户消耗（元）` | promotional-funding component | Medium-High | 不直接强制映射为 Credit |

### 官方组织能力提供的额外 Attribution Hint

官方文档确认：

```text
Organization
  ↓
Project
  ↓
Member
  ↓
API Key
```

且 Project API Key 的消费记入 Project。

这些是 Provider 侧归属维度，但企业内部仍可能需要映射：

```text
Kimi Project
→ Internal Project
→ Cost Center
```

---

## 5. GLM / 智谱

### 当前月度汇总文件

| 原始字段 | Canonical 候选 | 置信度 |
|---|---|---:|
| `账期(月)` | billing period | High |
| `目录总价` | list/catalog amount | Medium |
| `总消费金额` | provider consumption amount | High |
| `信用支付金额` | payment/funding component | Medium |
| `赠金抵扣金额` | deduction/promotional component | High |
| `应付金额` | payable amount | High |
| `已付款金额` | paid amount | High |
| `待付款金额` | outstanding amount | High |
| `结算状态` | provider settlement status | High |

### 重要限制

当前两条样本中字段关系不能由我们解释。

因此：

```text
禁止：
payable = consumption - promotional
```

除非官方定义或真实多样本能够证明。

### 官方详细费用文档补充

官方文档确认存在：

- 用量；
- 单价；
- 消费金额；
- 赠金抵扣；
- 应付；
- 已付/待付；
- 资源包/现金等扣减语义。

因此 GLM Adapter 后续可以区分“汇总账 parser”和“费用明细 parser”。

---

## 6. MiMo

### Model usage detail

| 原始字段 | Canonical 候选 | 置信度 |
|---|---|---:|
| `Date` | usage day | High |
| `Model` | model | High |
| `API Key` | attribution hint | High |
| `Currency` | currency | High |
| `Consumed Amount` | provider reported amount | High |
| `Input Hit Amount` | cost component: cache hit | High |
| `Input Miss Amount` | cost component: cache miss | High |
| `Output Amount` | cost component: output | High |
| `Total Tokens` | consumption aggregate | High |
| `Input Hit Tokens` | `INPUT_CACHE_HIT_TOKEN` | High |
| `Input Miss Tokens` | `INPUT_CACHE_MISS_TOKEN` | High |
| `Output Tokens` | `OUTPUT_TOKEN` | High |
| `Total audio duration` | audio consumption | High |
| `Request Count` | `REQUEST` meter | High |

### Plugin usage detail

```text
Date
Plugin
API Key
Currency
Consumed Amount
Request Count
```

当前没有记录，但 Header 可用于 schema contract fixture。

### Token Plan

官方文档确认：

```text
Token
→ 按模型、cache hit/miss/output 不同倍率
→ Credits
```

并存在：

```text
Subscription price
Credit quota
off-peak coefficient
```

因此：

- Token 是 Consumption；
- Credit 更接近 Pricing/Quota 单位；
- Subscription payment 是 Purchase/Commitment 类事实；
- 不能把三者塞入同一个 quantity 字段。

---

## 7. OpenAI API

### 本地空 Activity / Cost Export

当前仅有：

```text
start_time
end_time
start_time_iso
end_time_iso
```

因此本地样本只作为“Export 入口 + bucket 格式”证据。

### 官方 Organization Usage API

Completions Usage 可包含：

```text
input_tokens
input_cached_tokens
input_cache_write_tokens
input_uncached_tokens
output_tokens

input_text_tokens
input_audio_tokens
input_image_tokens
...

num_model_requests

project_id
user_id
api_key_id
model
batch
service_tier
```

映射：

| 官方字段 | Canonical |
|---|---|
| token fields | ConsumptionFact |
| `num_model_requests` | REQUEST ConsumptionFact |
| `project_id` | AttributionHint.provider_project |
| `user_id` | AttributionHint.provider_user |
| `api_key_id` | AttributionHint.provider_api_key |
| `model` | Provider service/model |
| `service_tier` | provider pricing/service metadata |

### 官方 Costs API

当前官方契约（2026-08-14 复核）group_by 支持：

```text
project_id
line_item
api_key_id
```

Costs result 当前支持：

```text
amount.value
amount.currency
api_key_id
line_item
project_id
quantity
object
```

其中 `line_item` / `project_id` / `api_key_id` 等维度字段可能根据
group_by / 返回形态为 optional / null。

映射：

- `amount` → ChargeFact/provider-reported cost；
- `line_item` → provider charge line；
- `quantity` → provider-native quantity，不猜通用 unit（不做 M3 PricingFact）；
- `api_key_id` → provider identity（`dimensions.credentialId`），不是 secret
  API key value；
- `project_id` → Attribution Hint。

---

## 8. 五家共同映射结果

### Consumption 维度

至少需要支持：

```text
INPUT_TOKEN
INPUT_CACHE_HIT_TOKEN
INPUT_CACHE_MISS_TOKEN
CACHE_WRITE_TOKEN
OUTPUT_TOKEN
REQUEST
AUDIO_DURATION
CREDIT
OTHER_PROVIDER_UNIT
```

但 `CREDIT` 是否属于 Consumption 还是 Pricing/Quota，需要根据具体 Provider context 区分。

所以最终更推荐：

```text
meter_code = Provider-neutral normalized code
raw_meter_code = 原始 Provider type
unit = TOKEN / CREDIT / REQUEST / SECOND / ...
```

---

## 9. Charge Category

借鉴 FOCUS，但不声明兼容：

```text
USAGE
PURCHASE
CREDIT
TAX
ADJUSTMENT
OTHER
```

额外保留：

```text
raw_charge_type
provider_metadata
```

如果 Provider 只给一个“Consumed Amount”，Adapter 不应擅自拆成多行 FOCUS 风格 Charge。

---

## 10. Money Model

不要使用：

```text
cost
real_cost
actual_cost
final_cost
```

候选语义：

```text
provider_reported_amount
billed_amount
cash_paid_amount
effective_allocated_amount
credit_amount
payable_amount
outstanding_amount
```

**并非每条 Charge 都有全部字段。**

V1 实现时应进一步收敛为最少必要字段，不为了“完整”强行填空。

---

## 11. Attribution Model

Provider Hint：

```text
provider_account
provider_organization
provider_project
provider_user
provider_api_key
provider_model
```

Internal Allocation：

```text
employee
team
internal_project
application
cost_center
allocation_rule
allocation_reason
```

必须保留来源：

```text
MANUAL
RULE
PROVIDER_HINT
IMPORT_MAPPING
```

以及：

```text
rule_version
decided_by
decided_at
```

---

## 12. Schema Evolution

Provider Adapter 必须允许：

- 新列；
- 缺列；
- 列顺序变化；
- 新 Sheet；
- 空文件；
- 新 meter type；
- 新 currency；
- 未知 wallet type。

原则：

> 未知字段不等于导入失败；影响核心语义的缺失字段才导致 Validation Error。

每个 Adapter 保存：

```text
provider
source_type
schema_fingerprint
parser_version
```

以支持后续重放和迁移。

---

## 12. M2 Group 2 落地矩阵与 Fixture 出处（2026-08-14）

Provider Adapters（#33–#37）实现的 schema 矩阵与解析器版本：

| Provider | Variant | Parser Version | Source Type |
|---|---|---|---|
| DeepSeek | `deepseek.usage-zip.v1` | `deepseek-provider-import-v1` | FILE_EXPORT |
| MiMo | `mimo.usage-workbook.v1` | `mimo-provider-import-v1` | FILE_EXPORT |
| Kimi | `kimi.billing-summary-workbook.v1` | `kimi-provider-import-v1` | FILE_EXPORT |
| GLM | `glm.monthly-billing-summary-workbook.v1` | `glm-provider-import-v1` | FILE_EXPORT |
| OpenAI | `openai.observed-empty-export.v1` | `openai-provider-import-v1` | FILE_EXPORT |
| OpenAI | `openai.organization-usage-completions-json.v1` | `openai-provider-import-v1` | USAGE_API_JSON |
| OpenAI | `openai.organization-costs-json.v1` | `openai-provider-import-v1` | COSTS_API_JSON |

Fixture 证据类别（详细清单见
`backend/src/test/resources/provider-fixtures/README.md`）：

```text
REAL_SCHEMA_SANITIZED      DeepSeek ZIP、MiMo workbook、Kimi 账单汇总、
                           GLM 月度汇总、OpenAI 空 CSV 导出（结构真实，值全脱敏）
OFFICIAL_SCHEMA_SYNTHETIC  OpenAI Usage completions JSON、Costs JSON
                           （基于 2026-08-14 复核的官方 API 契约）
SCHEMA_DRIFT_SYNTHETIC     各 Provider 的未知列/sheet/entry、缺失必需列、
                           错误 sourceType、ZIP 安全违规等测试 fixture
```

合成行绝不代表真实账户导出；真实原始文件不进入仓库。
