# ADR-012 — 单公共仓库 + 轻量 GitHub Flow

**状态：** Accepted
**日期：** 2026-08-12

## 背景

AI CostOps 由两个人协作开发，同时也是作品集项目。

如果把同一份成品复制到两个个人仓库，会：

```text
重复代码
产生 Mirror Drift
模糊真实协作历史
降低 Issue / PR / CI 证据价值
```

另一方面，如果所有 PR 都强制等待另一名 Contributor Approval，会给两人项目制造不必要的串行阻塞，尤其是在模块 Ownership 清晰、CI 与验收条件已经足够明确时。

## 决策

只使用一个公共仓库作为唯一 Source of Truth，并采用轻量 GitHub Flow：

```text
main
+ Short-lived Branch
+ Pull Request
+ Required Approvals = 0
+ Optional Peer Review
+ CI Gate（稳定后启用 Required Status Checks）
+ Squash Merge
```

约束：

```text
main 不直接开发
两名 Contributor 可自由 Push 自己的 Branch
PR Author 在满足仓库合并条件后可自行 Merge
Peer Review 用于重要、高风险或主动请求的变化
```

## 结果

优点：

```text
协作历史真实
同步简单
没有镜像漂移
不会因人工审批形成不必要等待
main 仍保持干净、线性的 PR 历史
CI 可逐步承担主要自动质量门禁
```

代价：

```text
并非每个 PR 都有 Peer Approval 记录
两个人简历链接到同一个 Repository
```

这些代价可以接受。项目不以“审批次数”包装协作质量，而以真实 Issue、PR、Commit、CI、测试证据和必要的 Code Review 体现工程协作。

每个人在简历中准确描述自己的模块 Ownership 即可。
