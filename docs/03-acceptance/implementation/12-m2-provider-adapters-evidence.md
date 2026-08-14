# 12. M2 Group 2 — Provider Adapters Acceptance Evidence

> Date: 2026-08-14
> Branch: `feat/m2-provider-adapters`
> Baseline: `main@9cb12f8`
> Issues: #33 (AIC-025), #34 (AIC-026), #35 (AIC-027), #36 (AIC-028), #37 (AIC-029)
> 验证命令与计数必须在最后一次 fresh verification 后填写；本文不包含猜测值。

## 1. Issue 映射

| Issue | AIC | Provider | 交付物 |
|---|---|---|---|
| #33 | AIC-025 | DeepSeek | `deepseek.usage-zip.v1` adapter + sanitized fixtures |
| #34 | AIC-026 | MiMo | `mimo.usage-workbook.v1` adapter + fixtures |
| #35 | AIC-027 | Kimi | `kimi.billing-summary-workbook.v1` adapter + fixtures |
| #36 | AIC-028 | GLM | `glm.monthly-billing-summary-workbook.v1` adapter + fixtures |
| #37 | AIC-029 | OpenAI | observed CSV + official Usage/Costs JSON adapters + fixtures |

## 2. 交付边界

本轮交付：

```text
Evidence
→ Inspect（schemaVariant + schemaFingerprint + issues）
→ Parse（streaming，bounded）
→ Provider-side intermediate normalization
→ RawProviderRecord / ImportIssue 持久化（redacted、lease-fenced、bounded）
→ PARSED / FAILED
```

明确不在本轮：

```text
M3 Canonical Cost（external_document / consumption_fact / pricing_fact /
charge_fact / attribution_hint）
live provider API client
import_attempt.schema_variant migration
GLM detailed-expense workbook
populated OpenAI CSV metric 契约
```

## 3. 关键不变量（均有回归测试）

- 五个 Provider Code 精确：`DEEPSEEK` / `MIMO` / `KIMI` / `GLM` / `OPENAI`；
  一个 Provider 一个注册 Adapter。
- `ImportSourceType` 是契约：`FILE_EXPORT` / `USAGE_API_JSON` / `COSTS_API_JSON`
  错配即 schema incompatible。
- Schema Fingerprint = canonical descriptor 的 SHA-256；业务值、行顺序、列顺序、
  sheet 顺序、ZIP entry 顺序、日期型文件名不进入 fingerprint；未知列进入
  fingerprint 且 WARN。
- 禁止推断：DeepSeek `amount*price=cost`；Kimi paid+promotional 合计 /
  FOCUS Credit；GLM settlement formula；MiMo Total/Components 相加；OpenAI
  `input_tokens + input_cached_tokens`。
- DeepSeek/MiMo 的 API-key 值在 adapter 层即剔除，仅保留固定 masked
  `credentialHint`；PayloadRedactor / IssueSanitizer 未被弱化。
- ZIP streaming（Commons Compress 统计）不落盘；XLSX 用 POI SAX 事件读取，
  禁止 `new XSSFWorkbook(inputStream)`，POI 默认 ZIP-bomb 防御未放松。
- OpenAI Costs 只使用当前官方字段 `amount.value / amount.currency / line_item /
  project_id`，不含过时假设 `api_key_id` / `quantity`。

## 4. Parser 依赖（冻结版本）

```text
Apache Commons CSV 1.14.1
Apache Commons Compress 1.28.0
Apache POI 5.5.1
```

## 5. 验证命令与真实结果

> 以下计数由最后一次 fresh verification（Task 14）实际运行后填写。

```powershell
# backend
.\mvnw.cmd -B "-Dtest=*ProviderAdapterTest,*StreamingReaderTest,SafeZipReaderTest,CsvSupportTest,SchemaFingerprintTest" test
.\mvnw.cmd -B "-DexcludedGroups=architecture,integration" test
.\mvnw.cmd -B "-Dgroups=integration" verify
.\mvnw.cmd -B "-Dgroups=architecture" test
# repository root
docker compose config --quiet
docker build --tag ai-costops-backend:m2-group2 backend
git diff --check
git status --short
```

### 结果占位（Task 14 填写）

```text
focused provider suite : Tests run: TBD, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
backend unit            : Tests run: TBD, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
backend integration     : Tests run: TBD, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
architecture            : Tests run: TBD, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
docker compose config   : exit 0
docker build            : SUCCESS
git diff --check        : no output
git status --short      : clean
```
