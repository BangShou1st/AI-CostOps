# ADR-010 — 前端采用 React 19 + TypeScript + Ant Design

**状态：** Accepted
**日期：** 2026-08-12

## 背景

AI CostOps 是企业工作流后台：

```text
table
filter
form
upload
drawer
approval
permission
audit
```

## 决策

```text
React 19
TypeScript
Vite
React Router
TanStack Query
Ant Design
ECharts
```

默认不引入 Redux。

## 影响

- TanStack Query 管 server state；
- React local/context 管少量 client state；
- 只有明确全局客户端状态需求再引入 Zustand/Redux。

## 不做的内容

不把项目做成 ECharts Dashboard 课程设计。
