# 01 — 蓝图 / 大纲设计包

这部分回答：

> 系统为什么这样做？产品边界是什么？核心领域和架构原则是什么？

## 开发者什么时候看

### 第一次进入项目

建议按顺序：

```text
product/05-product-scope.md
domain/06-glossary.md
domain/07-domain-model.md
domain/08-business-rules.md
architecture/10-system-architecture.md
architecture/13-iam-auth-design.md
architecture/14-docker-deployment.md
architecture/15-git-collaboration.md
```

### 做某个 Provider Adapter

再看：

```text
research/01-research-baseline.md
research/02-provider-mapping.md
research/03-data-strategy.md
```

### 要改变架构

先看对应 `architecture/adr/`。

## 这部分不负责什么

不拿它当每天写 Controller/SQL 的精确接口文档。

具体实现必须进入：

```text
docs/02-development/
```

## 最重要的冻结原则

```text
V1 Post-billing First
MySQL = Financial Truth
Redis != Financial Truth
Consumption != Pricing != Charge
Provider Identity = Attribution Hint
POSTED Ledger Immutable
Correction Append-only
V1 不做 Gateway / MQ / Microservices
```
