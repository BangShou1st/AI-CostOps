# 15. Git 双人协作架构

## 1. 仓库模型

AI CostOps 只使用**一个公共 GitHub 仓库**，两个人都是实际 Contributor。

```text
Developer A ─ feat/* ─ PR ─┐
                            ├─ protected main
Developer B ─ feat/* ─ PR ─┘
```

真实协作证据直接保留在：

```text
Issue
Commit
Pull Request
CI
Review Comment（按需）
```

不需要复制两个内容相同的个人镜像仓库。

## 2. Branch Model

唯一长期 Branch：

```text
main
```

短生命周期：

```text
feat/*
fix/*
refactor/*
test/*
perf/*
docs/*
chore/*
```

两人项目不长期维护：

```text
develop
release
integration
```

开发者可以自由创建、Commit、Push 自己的短生命周期 Branch；`main` 不直接开发。

## 3. main 保护

当前配置：

```text
Ruleset: Protect main
Enforcement: Active
Target: Default
Bypass: Empty

Restrict Deletions: On
Require Linear History: On
Require PR: On
Required Approvals: 0
Dismiss Stale Approvals: Off
Require Conversation Resolution: Off
Block Force Push: On
```

Merge：

```text
Merge Commit: Off
Squash: On
Squash Message: Pull request title
Rebase: Off
Auto-delete Head Branch: On
Auto-merge: Off initially
```

Required Status Checks 在 GitHub Actions Check 名真实出现并稳定后开启。届时 CI 是主要自动质量门禁，不依赖人工审批作为全局 Merge Gate。

## 4. Review

Peer Review 是**按需实践**，不是所有 PR 的前置条件。

建议互相 Review：

```text
财务核心不变量
复杂 Transaction / Concurrency
Ledger / Budget / Period Close
权限与安全边界
Provider Canonical Mapping
重要 Schema / Migration
重大架构变化
```

普通、低风险且 CI/验收充分的 PR，Author 可以自行 Squash Merge。

如果进行 Review，重点检查：

```text
业务不变量
Transaction
SQL / Index
Idempotency
Permission / Scope
Redis Truth Boundary
Migration
Failure Path
Test
```

## 5. AI-assisted Coding

AI 生成的代码仍然由提交 PR 的开发者负责。

必须：

```text
理解
修改
运行测试
提交 PR
对最终 Merge 负责
```

重要或高风险 AI-generated change 建议请求另一名 Contributor Review，但不作为全局强制条件。

AI 不是第三个无需负责的 Contributor。

## 6. 简历表述

建议：

> 双人协作项目，负责/主导具体模块，并通过 GitHub Issue、Pull Request、CI 及必要的 Code Review 协作开发。

如果 Git History 显示两个主要 Contributor，不写“个人独立完成全部系统”。
