# V1 Detailed Design 总览

> 版本：**1.1**
> 日期：**2026-08-12**
> 范围：**V1**
> 状态：**FROZEN / V1 DELIVERED（历史设计基线）**

这组文档位于：

```text
V0.2 Architecture Baseline
与
真正 Spring Boot / React 实现
```

之间。

目标不是把设计写得更长，而是消灭实现歧义。

## 已冻结内容

```text
模块边界
数据模型
状态机
事务边界
幂等
并发控制
REST API
权限与 Data Scope
Redis Key / TTL / Failure Policy
Provider Import Contract
React 信息架构
Error Model / Observability
Git Governance
仓库目录
.gitignore / Secret Policy
Configuration / Environment
```

## 核心原则

1. `MySQL` 是身份与财务业务最终 Truth。
2. `Redis` 负责 Session / TTL / Rate Limit / Cache，不承担 Ledger/Budget Truth。
3. 已经发生的 Provider Cost 即使超预算，也要如实入账。
4. Evidence、Failed Import Attempt、Raw Record 都保留 Traceability。
5. Provider User/API Key/Project 只是 Attribution Hint。
6. `POSTED Ledger` 不改写，错误通过 Correction 追加。
7. 无 FX Source/Version 时，不做跨币种单一总额。
8. 两个人只使用一个公共仓库，所有正式代码走短生命周期 Branch + PR；Peer Review 按需，不作为全局 Merge Gate。

## V1 明确不做

```text
Microservices
Kafka
Kubernetes
Elasticsearch
Realtime Gateway
FX Engine
SAML / SCIM
Approval DSL
完整 ERP GL
FOCUS Compliance
```

## 文档索引与阅读顺序

| # | 文档 | 主题 |
|---|---|---|
| 01 | [模块边界](01-module-boundaries.md) | 模块职责与依赖方向 |
| 02 | [数据模型](02-data-model.md) | 核心表与关系 |
| 03 | [状态机](03-state-machines.md) | 业务状态与转换 |
| 04 | [事务 / 幂等 / 并发](04-transactions-idempotency-concurrency.md) | 锁顺序、幂等键与并发边界 |
| 05 | API 契约 | 已独立升级到 [`../../api/`](../../api/)；机器契约以 [`../../api/openapi.yaml`](../../api/openapi.yaml) 为准 |
| 06 | [权限矩阵](06-permission-matrix.md) | 角色、权限与数据范围 |
| 07 | [Redis 设计](07-redis-design.md) | Key、TTL、失败策略 |
| 08 | [Provider Import](08-provider-import-design.md) | 导入契约与证据分类 |
| 09 | [React 信息架构](09-frontend-information-architecture.md) | 页面与导航 |
| 10 | [Error / Observability](10-error-model-observability.md) | 错误模型与可观测性 |
| 11 | [Git Governance](11-git-collaboration-governance.md) | 分支与协作治理 |
| 12 | [Implementation Readiness](12-implementation-readiness.md) | 开发启动门禁 |
| 13 | [Repository Layout](13-repository-source-layout.md) | 仓库目录 |
| 14 | [Repository Hygiene](14-repository-hygiene-gitignore.md) | .gitignore 与资产卫生 |
| 15 | [Configuration / Environment](15-configuration-environments.md) | 配置与环境变量 |

V1 已按此基线交付并冻结。本文档保留为历史设计基线：其中“审查通过后”的动作已经完成，
不再作为当前待办；维护期变更以 `PROJECT_CONTEXT.md`、`AGENTS.md` 与维护记录为准。


## API 开发入口

接口开发统一从 `../api/README.md` 开始，不再维护第二份独立 API Source of Truth。
