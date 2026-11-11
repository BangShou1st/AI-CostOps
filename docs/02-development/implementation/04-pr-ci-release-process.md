# 04. PR / CI / Release 流程

## 1. 标准开发链路

```text
[AIC-xxx] GitHub Issue
→ Owner
→ Branch
→ Code + Tests
→ PR
→ CI
→ Optional Review / Discussion
→ Squash Merge
→ main
```

## 2. Branch

优先使用 Backlog 推荐名。

如果拆 Issue，可以：

```text
feat/ledger-posting-core
test/ledger-posting-concurrency
```

PR 中保留：

```text
Plan: AIC-048
```

## 3. PR Title

因为 Squash 后 PR Title 会成为 main commit title，所以统一：

```text
feat(ledger): implement provider charge posting
test(budget): cover concurrent commitment activation
fix(import): recover expired worker lease
```

AIC 编号放 PR body，不要把 main commit title 变得很吵。

## 4. PR 必填信息

```text
What
Why
Design / Key Decisions
Testing
Database / Migration Impact
Risks / Failure Paths
Related Issue / Design
```

UI 变化加截图。

事务/SQL PR 加关键测试或 SQL 说明。

## 5. CI 门禁

`Protect main` ruleset 的 Required Checks（必须与 `.github/workflows/ci.yml` 实际作业名逐字一致）：

```text
backend-unit
backend-integration (shard 0)
backend-integration (shard 1)
frontend-quality (lint & build)
frontend-test
docker-build
ci-status
```

说明：

- ArchUnit 架构测试已并入 `backend-unit` / `gateway-unit` 的同一 Maven 运行，不再单独设置 `backend-architecture` 作业。
- 前端 lint 与 build 已合并为 `frontend-quality (lint & build)`。
- `ci-status` 聚合全部作业结果（含 gateway、browser-e2e、scripts），作为单一总门禁。
- **作业改名或合并时必须同步更新 Ruleset 的 Required Checks**；否则旧 context 永远不会上报，PR 将永久处于 `BLOCKED`。

以后可增加：

```text
compose-smoke
```

但只有稳定且不 Flaky 后才设为 Required。

## 6. Backend CI

### backend-unit

Domain/Application Unit Tests 与 ArchUnit 模块依赖规则在同一 Maven 运行中执行（`-DexcludedGroups=integration test`）。

### backend-integration

矩阵作业，按测试类名排序后拆分为 `(shard 0)` / `(shard 1)` 两个并行分片：

```text
Testcontainers MySQL
Redis when required
Flyway
关键事务测试
```

### backend-architecture

已并入 `backend-unit` 的同一 Maven 运行，不再单独设置作业。Check 名一旦变更，必须同步 Ruleset，避免 Required Checks 挂起。

## 7. Frontend CI

```text
frontend-quality (lint & build)
frontend-test
```

CI 使用 `npm ci`。

## 8. Docker CI

`docker-build` 只负责构建镜像，不要求外部部署。

## 9. Flyway Gate

任何包含：

```text
db/migration/**
```

的 PR 必须解释：

```text
为什么改 Schema
约束是什么
索引为什么存在
兼容性
是否有数据 Migration
```

已合并 Migration 不直接改内容，新增下一条 Migration。

## 10. 依赖升级

重大依赖升级不要混进业务 Feature PR。

使用独立：

```text
chore(deps): ...
```

重大版本变化必须先核对官方兼容性并跑完整受影响测试。

## 11. 建议请求 Peer Review / 暂缓 Merge 的情况

```text
违反 INV-* 规则
Redis 变成财务 Truth
核心事务没有失败路径测试
提交真实 Secret / Provider 原始文件
缺少 Flyway
权限只在前端控制
Provider Mapping 靠猜
一个 PR 混进大量无关变化
```

## 12. main 出问题怎么办

不要关闭 Branch Protection 直接修。

仍然：

```text
fix/*
→ PR
→ CI
→ 必要时 Review
→ Merge
```

## 13. Milestone Close

只有：

```text
Required Issues merged
main Green
Milestone Acceptance Test 通过
Deferred 已记录
```

才 Close Milestone。

## 14. v1.0.0 前

必须至少具备：

```text
Compose Smoke
Provider E2E
Expense E2E
Performance Report
Failure Report
Security Review
准确 README
```

## 15. GitHub Release

Release Notes 写：

```text
V1 做什么
支持哪些 Provider 输入形态
技术栈
实际测试证据
Known Limitations
如何运行
```

不要写“Production Ready”，除非未来真的有相应证据。
