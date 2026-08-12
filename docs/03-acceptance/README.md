# 03 — 测试 / 验收包

这部分回答：

> “做完”到底怎么证明？什么时候可以 Merge？什么时候可以叫 V1 完成？

## 日常 PR 验收

先看：

```text
01-PR验收清单.md
quality/12-test-and-validation.md
```

## Milestone 验收

看：

```text
02-Milestone验收证据矩阵.md
quality/12-test-and-validation.md
```

M0 Foundation 的本地实现证据：

```text
implementation/08-m0-foundation-evidence.md
```

## v1.0.0 最终验收

看：

```text
implementation/06-v1-release-acceptance.md
02-Milestone验收证据矩阵.md
implementation/07-risk-register.md
```

## 重要原则

不能用：

```text
“我本地跑过”
“页面看起来没问题”
“AI 说测试通过”
```

替代真实证据。

需要按任务保存：

```text
CI Result
Test Output
Integration Test
Concurrency Test
EXPLAIN
Benchmark Report
Compose Smoke
Known Limitations
```

性能数字只能在真实 Benchmark 后写入 README / 简历。
