# CO-00 CI 结果

> 目标提交：`main @ 57696e25ca444658ec114a8152e7224f2fa67e45`
> 运行时间：2026-11-11（GitHub Actions，push 事件）

## 1. 汇总

| Workflow | Run ID | 事件 | 结论 | 作业数 |
|---|---|---|---|---|
| CI | `42951085970` | push | **success** | 14/14 |
| Security | `42951085983` | push | **success** | 3/3 |

## 2. CI 作业明细

```text
detect-changes: success
gateway-unit: success
scripts-unit (ubuntu-latest): success
backend-unit: success
scripts-lifecycle: success
gateway-integration: success
backend-integration (shard 1): success
scripts-unit (windows-latest): success
frontend-quality (lint & build): success
docker-build: success
browser-e2e: success
frontend-test: success
backend-integration (shard 0): success
ci-status: success
```

`backend-unit` 同时执行 Surefire 单元测试与 ArchUnit 架构测试；`backend-integration` 按测试类排序拆为两个矩阵分片；`ci-status` 聚合全部作业结果，并已作为 `Protect main` ruleset 的必需检查。

## 3. 历史对照（不作为当前结论）

| Run ID | 分支 / SHA | 事件 | 结论 |
|---|---|---|---|
| `42578509717` | `perf/ci-acceleration @ e2aa26e` | pull_request | success |
| `42646521102` | `main @ 1fb5e74` | schedule | failure（Trivy `follow-redirects` HIGH） |

## 4. 复现命令

```bash
gh run view 42951085970 --json workflowName,status,conclusion,headSha
gh run view 42951085970 --json jobs --jq '.jobs[] | "\(.name): \(.conclusion)"'
gh run view 42951085983 --json jobs --jq '.jobs[] | "\(.name): \(.conclusion)"'
```

## 5. 说明

- PR #174 合并前的最后一次 PR 门禁曾出现一次 `ExpenseEvidenceIntegrationTest` 的 MockMvc 异步下载竞态（同一 SHA 的并行运行通过），重跑后通过；该 flake 属测试侧既有竞态，本轮未改动该测试，已记录在维护遗留中。
- PR #174 的最终门禁为 `CLEAN`，14 项必需检查全部通过后以 squash 合并；合并后 `main` push 再次完整运行 CI 与 Security，即上表结果。
