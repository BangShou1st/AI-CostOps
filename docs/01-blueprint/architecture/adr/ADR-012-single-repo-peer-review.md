# ADR-012 — 单公共仓库 + Peer Review GitHub Flow

**状态：** Accepted
**日期：** 2026-08-12

## 背景

AI CostOps 由两个人协作开发，同时也是作品集项目。

如果把同一份成品复制到两个个人仓库，会：

```text
重复代码
产生 Mirror Drift
模糊真实协作历史
降低 PR / Review 证据价值
```

## 决策

只使用一个公共仓库作为唯一 Source of Truth：

```text
main
+ Short-lived Branch
+ Pull Request
+ 1 Peer Approval
+ Conversation Resolution
+ CI Gate
+ Squash Merge
```

## 结果

优点：

```text
协作历史真实
同步简单
没有镜像漂移
Owner / Reviewer 清楚
```

代价：

```text
两个人简历链接到同一个 Repository
```

这个代价可以接受；每个人在简历中准确描述自己的模块 Ownership 即可。
