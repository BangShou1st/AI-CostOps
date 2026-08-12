# V1 Detailed Design 总览

> 版本：**1.1**
> 日期：**2026-08-12**
> 范围：**V1**
> 状态：**一次性审查候选版**

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

## 阅读顺序

```text
01 模块边界
02 数据模型
03 状态机
04 事务 / 幂等 / 并发
05 API 契约已独立升级到 `../api/`；开发接口时以 `../api/openapi.yaml` 为机器可读基线。
06 权限矩阵
07 Redis
08 Provider Import
09 React 信息架构
10 Error / Observability
11 Git Governance
12 Implementation Readiness
13 Repository Layout
14 Repository Hygiene
15 Configuration / Environment
```

## 审查通过后

```text
Freeze Detailed Design 1.1
→ 执行 Implementation Plan 1.0
→ 创建 GitHub Issues
→ 开发
```

除非实现发现真实矛盾，否则不再新增一轮“Detailed Design 2.0”。


## API 开发入口

接口开发统一从 `../api/README.md` 开始，不再维护第二份独立 API Source of Truth。
