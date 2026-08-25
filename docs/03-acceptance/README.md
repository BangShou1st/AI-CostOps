# 03 — 测试 / 验收包

这部分回答：

> “做完”到底怎么证明？什么时候可以 Merge？什么时候可以叫 V1 完成？

当前状态：

```text
V1 = COMPLETE / FROZEN
AIC-073 = COMPLETED / ACCEPTED
Current stable release = v1.0.1
```

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

最终结论：

```text
AIC-073 = COMPLETED
Human acceptance = ACCEPTED
P0/P1 = 0
Release blocker = NONE
RELEASE_READY = YES
```

## Release Outcome

AIC-073 sign-off 后已完成正式发布：

```text
v1.0.0
→ 982d06a0e9ec844ea687ed746d6b9d8f39d86686
→ Published
```

发布后，main push CI 暴露出 Import lease recovery 在 cancel/recover race 下的 transient MySQL deadlock flake。PR #103 以 bounded deadlock retry 做最小加固，并在 Java 21 GitHub Actions 上通过 7/7 CI；合并后的 main push CI 也成功。随后发布：

```text
v1.0.1
→ b96be614e2d843c101add49fe6daffb9d2343a56
→ Published
```

`v1.0.0` tag 保持固定，不因后续补丁移动。`v1.0.1` 不改变 API、Schema 或 V1 产品范围。

## Frozen Evidence Policy

以下材料是 V1 的时间点历史证据，应保留其当时状态，不为了“看起来最新”而改写：

```text
v1-release-candidate-evidence.md
AIC-071 / AIC-072 RC evidence
AIC-073 final sign-off baseline
M0–M8 milestone evidence
V1 benchmark reports
```

当前入口文档负责说明“现在是什么状态”；历史证据负责说明“当时如何证明”。
