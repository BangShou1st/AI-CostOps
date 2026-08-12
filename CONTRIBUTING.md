# AI CostOps 贡献与协作规范

## 1. 开发流程

```text
GitHub Issue
→ Branch
→ Code + Tests
→ Pull Request
→ CI
→ Optional Review / Discussion
→ Squash Merge
```

禁止直接在 `main` 开发。

## 2. 开始一个 Issue

```bash
git switch main
git pull --ff-only origin main
git switch -c <branch-name>
```

Branch：

```text
feat/*
fix/*
refactor/*
test/*
perf/*
docs/*
chore/*
```

## 3. Commit

使用 Conventional Commit 风格：

```text
feat(iam): implement password login
feat(provider): add MiMo adapter
fix(import): recover expired worker lease
test(budget): cover concurrent activation
docs(api): update budget contract
chore(ci): add backend workflow
```

避免：

```text
update
final
fix
111
改一下
```

## 4. Pull Request

PR 必须说明：

```text
What
Why
Design / Key Decisions
Testing
Database / Migration Impact
Risks / Failure Paths
Related Issue / Design
```

数据库变化必须包含 Flyway。

## 5. API Change

任何 API 变化必须同步：

```text
docs/02-development/api/openapi.yaml
Controller / DTO
Frontend Type / Client
Contract / Integration Test
```

禁止口头修改接口。

## 6. Review

Peer Review 不是全局 Merge Gate。重要模块、高风险变更或作者主动请求时，建议由另一名 Contributor Review。

如进行 Review，优先检查相关项：

```text
业务不变量
Transaction
Idempotency
Concurrency
SQL / Index
Permission / Scope
Redis Truth Boundary
Migration
Failure Path
Test
```

普通讨论使用 Comment 即可；是否需要修改由 PR Author 结合设计、测试和 CI 结果处理。

## 7. Secret / Provider Data

禁止提交：

```text
.env
真实密码
JWT Signing Key
Provider API Key
真实 Provider 原始导出文件
本地数据库数据
日志
Build Output
```

Provider Test Fixture 只允许：

```text
REAL_SCHEMA_SANITIZED
OFFICIAL_SCHEMA_SYNTHETIC
SYNTHETIC_ENTERPRISE
```

## 8. AI-assisted Coding

AI 生成代码的责任仍属于 PR Author。

合并前必须：

```text
理解代码
运行测试
能解释设计
必要时接受 Peer Review
```

## 9. 更详细规范

开发：

```text
docs/02-development/
```

Review / 验收：

```text
docs/03-acceptance/
```
