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
v1-release-candidate-evidence.md
aic-073-final-human-acceptance.md
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
Human UAT / Release Sign-off
```

性能数字只能在真实 Benchmark 后写入 README / 简历。

## V1 Final Acceptance

AIC-071 + AIC-072 的 Release Candidate 证据见：

```text
superpowers/specs/2026-08-23-m8-compose-smoke.md
v1-release-candidate-evidence.md
```

`v1-release-candidate-evidence.md` 的 Final boundary 记录的是 AIC-072 RC 冻结时点的历史状态；其中 `AIC-073: FROZEN / NOT EXECUTED` 与 `Human acceptance: PENDING` 已由后续 AIC-073 最终签署记录 supersede，不应再解释为当前状态。

AIC-073 Final Human Acceptance / Release Sign-off 已完成，最终记录见：

```text
aic-073-final-human-acceptance.md
```

当前结论：

```text
AIC-073 = COMPLETED
Human acceptance = ACCEPTED
P0/P1 = 0
Release blocker = NONE
RELEASE_READY = YES
```

`v1.0.0` tag 与 GitHub Release 只能在 AIC-073 sign-off PR 合并后、以合并后的 `main` HEAD 创建。