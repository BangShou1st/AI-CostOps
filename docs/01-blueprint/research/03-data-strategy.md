# 03. Data & Validation Strategy

## 1. 为什么不能把“真实格式”当成“真实企业数据”

当前拥有的 Provider 文件主要用于验证：

- 文件格式；
- Header；
- Sheet；
- 数据粒度；
- Provider 暴露哪些概念。

它们不能证明：

- 企业人数/项目规模；
- 真实费用分布；
- 真实审批链；
- 真实异常发生率；
- 真实月末关账流程。

因此开发测试数据必须明确分层。

---

## 2. 四层数据体系

### Layer A — Provider Schema Fixtures

来源：

- DeepSeek ZIP；
- Kimi XLSX；
- GLM XLSX；
- MiMo XLSX；
- OpenAI CSV；
- 官方 API 示例。

用途：

- Parser contract test；
- Schema version test；
- Empty file test；
- Unknown-column compatibility test。

这些 fixture 不改写成“企业数据”。

---

### Layer B — Public / Official Usage Examples

来源优先级：

1. Provider 官方 API response example；
2. Provider 官方文档示例；
3. 有出处的公开 AI usage dataset。

用途：

- Token/Request/Cache 分布参考；
- 生成合理的 Metering data；
- 验证高低用量长尾。

注意：

> 公开 Coding Agent trace 不等于企业账务数据。

---

### Layer C — FOCUS / FinOps Billing Semantics

使用 FOCUS 1.4 及其 Sandbox/公开示例理解：

- Billing Period；
- Credit；
- Correction；
- Invoice；
- Reconciliation；
- Cost granularity；
- null / multi-provider normalization。

用途是借鉴“账务行为”，不是把 AI Provider 数据伪装成 FOCUS 原始数据。

---

### Layer D — Synthetic Enterprise Dataset

我们最终压测/业务验证使用的企业数据必须明确标记：

> **SYNTHETIC**

生成依据：

```text
Provider raw schemas
+
public/official usage distributions
+
FOCUS-inspired billing behavior
+
project-defined business rules
```

禁止：

> “仿真数据 = 真实企业数据”

---

## 3. 第一版 Synthetic Company Profile

这是**测试场景假设**，不是市场事实。

```text
Employees:        1,000
AI-active users:    300
Teams:               20
Projects:             50
Cost Centers:         15
Providers:             5
Billing periods:       6 months
```

目标数据规模：

```text
100k
500k
1m
```

Normalized Fact records。

---

## 4. 必须注入的异常类型

生成器不只生成 Happy Path。

### Evidence / Import

- 相同文件重复上传；
- 文件改名但内容相同；
- CSV 重新保存导致文件 hash 不同；
- 两份账单时间范围重叠；
- Provider 新增未知列；
- Provider 缺失关键列；
- 一部分记录 parse failure；
- Import 在中途 crash。

### Cost / Metering

- Usage 有、Cost 无；
- Cost 有、Usage 无；
- negative correction；
- promotional credit；
- subscription purchase；
- multiple currencies；
- unknown meter；
- zero-cost usage。

### Attribution

- API Key 无映射；
- 一个 provider project 映射多个内部项目；
- 映射规则版本变化；
- 手工归属覆盖自动规则。

### Ledger

- 重复 posting；
- post 后发现项目错误；
- close 后发现漏账；
- adjustment 二次 adjustment。

### Reconciliation

- exact match；
- rounding tolerance match；
- missing internal charge；
- missing invoice line；
- credit difference；
- tax difference；
- currency difference；
- unknown difference。

### Budget

- 同时两个 commitment 超过 available；
- approval 已通过但 posting 失败；
- cancel commitment 后额度释放。

---

## 5. 数据可追溯性

所有 synthetic record 至少保存：

```text
dataset_version
scenario_id
generator_seed
expected_outcome
```

这样失败测试可以重放。

---

## 6. 禁止使用的数据策略

不要：

- 用纯 `random()` 均匀生成 Token；
- 伪造“某大厂员工使用数据”；
- 直接把 MiMo 三行数据复制几十万倍称作真实负载；
- 把公开 benchmark 的 cost 当成企业 invoice；
- 为了测试方便删除 Attribution/Invoice/Correction 等难场景。

---

## 7. 最终目标

数据的价值不在“看起来真实”，而在：

> **它能稳定触发我们声称已经解决的业务问题，并且测试有可验证的正确答案。**
