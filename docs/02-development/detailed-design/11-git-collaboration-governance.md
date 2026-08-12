# 11. Git 协作治理

## 1. 仓库模型

只使用一个 Public GitHub Repository。

```text
Developer A ─ Branch ─ PR ─┐
                            ├─ Protected main
Developer B ─ Branch ─ PR ─┘
```

不复制成两个内容相同的“个人版仓库”。

## 2. main Ruleset

当前冻结：

```text
Ruleset: Protect main
Status: Active
Target: Default Branch
Bypass: Empty

Restrict Deletions: On
Require Linear History: On
Require Pull Request: On
Required Approvals: 1
Dismiss Stale Approvals: On
Require Conversation Resolution: On
Block Force Push: On
```

`Require Status Checks` 等 CI Check 名稳定后开启。

## 3. Merge Setting

```text
Merge Commit: Off
Squash: On
Rebase: Off
Squash Default Message: Pull request title
Auto-delete Head Branch: On
Auto-merge: Off initially
```

目标：

```text
1 PR
→ main 上 1 个干净 Commit
```

## 4. Branch 命名

```text
feat/<name>
fix/<name>
refactor/<name>
test/<name>
perf/<name>
docs/<name>
chore/<name>
```

示例：

```text
feat/iam-login
feat/mimo-import
feat/ledger-posting
fix/import-retry-idempotency
test/budget-concurrency
docs/v1-detailed-design
chore/ci-backend
```

不把开发者姓名放进 Branch Name。

## 5. 每日开始

```bash
git switch main
git pull --ff-only origin main
git switch -c feat/...
```

禁止直接在 main 开发。

## 6. Commit 规范

使用 Conventional Commit 风格：

```text
feat(iam): implement refresh session
feat(provider): add MiMo parser
fix(import): preserve failed attempt records
test(budget): cover concurrent activation
perf(ledger): add period project index
refactor(cost): isolate charge normalization
docs(design): update data model
chore(ci): add backend workflow
```

禁止：

```text
update
final
fix
改一下
111
```

Feature Branch 上可以有 Review Fix Commit，最终由 Squash 清理 main History。

## 7. Issue 规范

非小改动必须先有 GitHub Issue。

Issue 至少写：

```text
背景
范围
Acceptance Criteria
相关 Design
Dependency
Owner
```

Label 初始只保留真正有用的：

```text
type:feature
type:bug
type:test
type:docs
type:performance

area:iam
area:import
area:provider
area:ledger
area:budget
area:reconciliation
area:frontend
area:infra

priority:P0/P1/P2
status:blocked
```

不要一开始建几十种 Label。

## 8. PR Template

建议：

```markdown
## What

## Why

## Design / Key Decisions

## Testing

## Database / Migration Impact

## Risks / Failure Paths

## Screenshots
<!-- 仅 UI 变化 -->

## Related
Closes #
Plan: AIC-xxx
Design:
```

## 9. PR 粒度

原则：

> 一个 PR = 一个可审查的逻辑变化。

禁止：

```text
Implement V1 Backend
+ 20,000 Lines
```

Ledger 可以拆：

```text
Schema
Posting
Query
Correction
Invariant Tests
```

## 10. Review Checklist

### Business

```text
是否违反 INV-*？
State Transition 是否一致？
```

### DB

```text
Transaction？
Constraint？
Index？
BigDecimal？
Flyway？
```

### Concurrency

```text
Retry Safe？
Idempotent？
Race？
Lock Order？
```

### Redis

```text
Cache 还是 Truth？
TTL？
Failure Policy？
```

### Security

```text
Permission / Scope？
Secret Logging？
Raw Evidence Access？
```

### Test

```text
Happy Path？
Failure Path？
Invariant Test？
Integration Test？
```

该 Request Changes 时就 Request Changes，不做秒 Approve。

## 11. 双人 Review

```text
Dev A Author → Dev B Reviewer
Dev B Author → Dev A Reviewer
```

作者不能自己满足 Required Approval。

## 12. CODEOWNERS

两个人 GitHub Username 最终确定后再提交：

```text
.github/CODEOWNERS
```

用途是 Ownership / Reviewer Routing。

两人项目初期不额外强制 `Require Code Owner Approval`，避免 Owner 自己提交时产生不合理流程。

## 13. CI

稳定 Check：

```text
backend-unit
backend-integration
backend-architecture
frontend-lint
frontend-test
frontend-build
docker-build
```

这些都真实跑过后，加入 main Ruleset。

`compose-smoke` 只有稳定后才 Required。

## 14. Flyway Review

Migration PR 必须解释：

```text
为什么改 Schema
Constraint
Index
Compatibility
Data Migration
Failure / Recovery
```

禁止本地手改 DB 不写 Migration。

## 15. AI-assisted Development

AI 生成代码也必须：

```text
人类理解
人类运行测试
人类提交 PR
另一个人 Review
```

PR Author 对代码负责。

## 16. main History

预期：

```text
chore(repo): bootstrap monorepo
feat(iam): implement password login
feat(provider): add MiMo adapter
feat(ledger): implement immutable posting
```

## 17. Release Tag

不需要 `release/*` Branch。

候选：

```text
v0.1.0 Skeleton + IAM
v0.2.0 Evidence / Import
v0.3.0 Facts / Allocation
v0.4.0 Budget / Expense / Ledger
v0.5.0 Reconciliation / Close
v1.0.0 V1 DoD
```

只有功能真的达到 Milestone 才 Tag。
