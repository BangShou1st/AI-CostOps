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
Review Comment
Approval
CI
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
Required Approvals: 1
Dismiss Stale Approvals: On
Require Conversation Resolution: On
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

Required Status Checks 在 GitHub Actions Check 名真实出现并稳定后开启。

## 4. Review

```text
A 写 → B Review
B 写 → A Review
```

Review 不只是语法检查，还要检查：

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

有问题正常使用 `Request Changes`。

## 5. AI-assisted Coding

AI 生成的代码仍然由提交 PR 的开发者负责。

必须：

```text
理解
修改
运行测试
提交
接受 Peer Review
```

AI 不是第三个无需负责的 Contributor。

## 6. 简历表述

建议：

> 双人协作项目，负责/主导具体模块，并通过 GitHub Issue、Pull Request、Code Review、CI 进行协作开发。

如果 Git History 显示两个主要 Contributor，不写“个人独立完成全部系统”。
