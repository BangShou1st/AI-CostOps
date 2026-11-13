# V1 实施计划总览

> 版本：**1.0**
> 日期：**2026-08-12**
> 状态：**V1 历史实施计划（已完成，保留为实施基线）**
> 前置：V1 Detailed Design 1.1

这组文档把已经确认的架构设计转成**两个人可以直接开发的工程任务**。

它是 V1 正式编码前的最后一层计划。V1 已发布，以下内容不再作为当前待办；
维护期入口见 [`AGENTS.md`](../../../AGENTS.md) 与 [`PROJECT_CONTEXT.md`](../../../PROJECT_CONTEXT.md)。

历史执行顺序（已完成）：

```text
冻结设计
→ 创建 GitHub Milestones / Issues
→ M0 Repository Foundation
→ 正式开发
```

## 文档组成

| 文档 | 用途 |
|---|---|
| [开发启动门禁](00-development-start-gate.md) | 开工前的设计、环境与范围检查 |
| [Milestone 与依赖关系](01-milestones-dependency-graph.md) | M0 ~ M9 的实施顺序 |
| [完整 Issue Backlog](02-issue-backlog.md) | AIC-001 ~ AIC-073 历史任务清单 |
| [M9 Issue Backlog](03-m9-issue-backlog.md) | M9 生产基础收尾任务 |
| [双人任务分配](03-two-person-work-allocation.md) | V1 双人协作分工 |
| [PR / CI / Release 流程](04-pr-ci-release-process.md) | 历史发布流程；现行流程以 `CONTRIBUTING.md` 为准 |
| [本地开发与 Bootstrap Runbook](05-bootstrap-local-development-runbook.md) | 本地环境与启动步骤 |
| [Authentication 本地开发](06-authentication-local-development.md) | 登录、会话与本地邮箱 |
| [M11 Gateway 本地 Runbook](06-m11-gateway-local-runbook.md) | Gateway 本地开发与验收 |
| [M9 实施记录入口](README-M9.md) | M9 阶段文档导航 |
| [V1 发布验收](../../03-acceptance/implementation/06-v1-release-acceptance.md) | V1 发布验收证据 |
| [风险清单与技术检查点](../../03-acceptance/implementation/07-risk-register.md) | V1 风险登记 |

## AIC 编号

计划内部使用稳定编号：

```text
AIC-001
AIC-002
...
AIC-073
```

GitHub 会另外生成 Issue #。

例如：

```text
AIC-021
→ GitHub Issue #37
```

GitHub Issue 标题建议保留：

```text
[AIC-021] 实现 MiMo Model Usage Adapter
```

这样文档引用不受 GitHub Issue 序号变化影响。

## Owner 约定

```text
Dev A
= 架构 / 财务核心主责

Dev B
= IAM / Ingestion / Workflow 主责
```

主责不代表独占。Peer Review 按风险和需要进行，不作为所有 PR 的 Merge Gate。

## 一个 Issue 对应什么

正常情况下：

```text
1 个 AIC Issue
→ 1 个可审查 PR
```

如果实现时发现一个 Issue 太大，可以拆分子 Issue，但必须：

```text
保留原 AIC 编号引用
不擅自改变架构
不把多个无关需求塞进一个 PR
```

## 哪些变化不需要重新设计

例如：

```text
类名
DTO 小调整
批量大小
具体 patch 版本
测试 Helper
少量目录调整
```

可以在 PR 中直接处理。

## 哪些变化必须改设计 / ADR

例如：

```text
MyBatis → JPA
Redis 成为 Budget Truth
Mutable Ledger
V1 引入 Gateway
V1 强制引入 MQ
微服务拆分
自动 FX
多租户 SaaS 语义
```

这些不是实现细节。

## 实施计划完成标准

本计划已经覆盖：

```text
Milestone
依赖图
Issue 级拆分
Owner / Suggested Reviewer
Branch 建议
必要测试
CI 门禁
本地启动
V1 Release Acceptance
风险检查点
```

审查通过后，不再新增一层泛化 Implementation Plan。
