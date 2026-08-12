# 04. PR / CI / Release 流程

## 1. 标准开发链路

```text
[AIC-xxx] GitHub Issue
→ Owner
→ Branch
→ Code + Tests
→ PR
→ CI
→ Peer Review
→ Resolve
→ Approve
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

M0 稳定后 Required Checks：

```text
backend-unit
backend-integration
backend-architecture
frontend-lint
frontend-test
frontend-build
docker-build
```

以后可增加：

```text
compose-smoke
```

但只有稳定且不 Flaky 后才设为 Required。

## 6. Backend CI

### backend-unit

快速 Domain/Application Unit Tests。

### backend-integration

```text
Testcontainers MySQL
Redis when required
Flyway
关键事务测试
```

### backend-architecture

ArchUnit 模块依赖规则。

Check 名尽量保持稳定，避免 Ruleset 反复修改。

## 7. Frontend CI

```text
frontend-lint
frontend-test
frontend-build
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

## 11. 必须 Request Changes 的情况

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
→ Review
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
