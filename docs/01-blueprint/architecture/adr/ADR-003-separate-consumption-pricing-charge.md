# ADR-003 — Consumption / Pricing / Charge 分离

**状态：** Accepted

## 背景

DeepSeek/OpenAI 把 Usage 与 Cost 分开；MiMo 同时存在 Token、Amount、Credit；GLM/Kimi 还有抵扣/结算语义。

## 决策

核心领域不采用万能 `provider + tokens + cost` 模型。

分为：

```text
Consumption
Pricing (optional)
Charge
```

## 影响

优点：

- 能表达 Token != Credit != Money；
- 能容纳 Provider 不同粒度；
- 财务和用量不相互污染。

代价：

- 模型更复杂；
- 某些简单 Provider 看起来需要多一步 mapping。

## 约束边界

没有外部证据时不强行生成 PricingFact。
