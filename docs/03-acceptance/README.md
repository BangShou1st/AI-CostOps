# 03 — 测试 / 验收包

这部分回答：

> “做完”到底怎么证明？各版本与里程碑的验收证据在哪里？

当前项目状态：

```text
FINAL_PRODUCT_VERSION = V3
STABLE_RELEASE = v3.0.0
Feature development = CLOSED
Operational mode = MAINTENANCE_ONLY
V4 = NOT_PLANNED
M20 Production Acceptance = COMPLETE
M21 Operational Readiness = COMPLETE
M22 = M22_CLOSED_WITH_DEFERRED_NON_BLOCKING_GATES
M22_BROWSER_ORGANIZATION_ISOLATION = PASS (2026-10-19 supplementary)
Known release-blocking defects = NONE
```

M22 原始关闭记录中的两个 gate 当时均为 `DEFERRED / NON-BLOCKING`。其中 independent browser-context organization-isolation 已由 2026-10-19 补充验收完成并判定 PASS（含后续独立复审发现的模型可见性边界整改），见 [2026-10-19 M22 双组织浏览器隔离补充验收](2026-10-19-m22-browser-organization-isolation.md)；real-provider / OpenCode external credential 仍为 `DEFERRED / NON-BLOCKING`，不随浏览器隔离改为 PASS。历史记录 [M22 v3 production certification](m22-v3-production-certification.md) 保留其原时间点结论，未改写。其他里程碑见 [M20](m20-v3-production-acceptance.md) 与 [M21](m21-v3-post-release-operational-readiness.md)。下方 V1 / V1.1 条目是历史验收证据。

## 最新 dev 维护验收（2026-10-18 / 2026-10-19）

以下记录基于 `dev @ c5142089bb764a0c878e8761af01854b08045068` 及其近期维护提交，均为已在 `dev` 存在的维护与验收成果：

- dev 分支 CI 14/14、Security 3/3 全绿：[dev 分支 CI/Security 启用与首轮远程验收](2026-10-18-dev-branch-ci-security-enablement.md)
- Gateway → Usage → Settlement → Ledger → Budget Actual 财务闭环（真实 MySQL + Mock Provider）：[dev 财务闭环验收](2026-10-18-dev-financial-closed-loop-acceptance.md)
- M21 运维脚本、Gateway 最小权限（`LOCK TABLES`）与财务 Smoke 强化：[M21 运维验收修复](2026-10-19-m21-ops-acceptance-fix.md)
- M21 Advisor Worker 最小数据库权限补齐：[Advisor Worker 最小数据库权限](2026-10-19-advisor-worker-minimal-db-privileges.md)
- M22 双组织、双独立 `BrowserContext` 隔离及模型可见性边界整改：[M22 双组织浏览器隔离补充验收](2026-10-19-m22-browser-organization-isolation.md)，最新结论 `M22_BROWSER_ORGANIZATION_ISOLATION = PASS`
- OpenCode Zen minimax 模型真实浏览器验收（Edge + BrowserSkill 真实 UI + 真实上游推理）：[OpenCode Zen minimax 真实浏览器验收](2026-10-22-opencode-zen-minimax-real-browser-acceptance.md)

OpenCode Zen / Kilo Gateway 的接入与协议维护、Cost Intelligence 历史覆盖与输入身份改进、结算 Worker 可靠性修复等变更的逐项记录见本目录 `2026-10-*` 文档。

M9 总入口：`m9-acceptance-matrix.md`；最终收敛报告：`aic-083-m9-final-acceptance.md`；逐项证据见本目录 `m9-*.md`。

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
