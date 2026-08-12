# 01. Milestone 与依赖关系

## 1. Milestone 总览

| Milestone | 目标 | 退出结果 |
|---|---|---|
| M0 | Repository Foundation | 可构建 Monorepo、Compose 基础、CI 门禁 |
| M1 | Identity & Organization | 登录、Session、权限、组织与项目主数据 |
| M2 | Evidence & Import | Evidence 存储、DB Job、Provider Adapter 基线 |
| M3 | Canonical Cost & Attribution | Canonical Facts、重复审查、归属决策 |
| M4 | Expense & Budget | 费用审批、BillingPeriod、Budget Commitment |
| M5 | Immutable Ledger | Posting、幂等、Correction、Lineage |
| M6 | Reconciliation & Close | 对账、Close Blocker、Close/Reopen |
| M7 | Workbench & Integration | 完整 React 工作流与 E2E |
| M8 | Hardening & Release | 故障、并发、性能、安全、Compose、v1.0.0 |

## 2. 关键依赖图

```text
                         M0
                  Repository Foundation
                         |
              +----------+----------+
              |                     |
              v                     v
             M1                 Shared DB/Test
      IAM + Organization          Foundation
              |                     |
              +----------+----------+
                         |
                         v
                        M2
                 Evidence & Import
                         |
                         v
                        M3
          Canonical Cost + Attribution
                 /               \
                v                 v
            Expense            Period/Budget
                 \               /
                  +------+------+
                         v
                        M5
                    Immutable Ledger
                         |
                 +-------+-------+
                 |               |
                 v               v
          Reconciliation    Period Close
                 \               /
                  +------+------+
                         v
                        M7
             Workbench / Integration
                         |
                         v
                        M8
               Hardening / Release
```

## 3. 双人并行窗口

### W0 — M0

```text
Dev A
→ Backend / DB / Test / Docker 基础

Dev B
→ Frontend / Repo Governance 基础
```

### W1 — M1

```text
Dev A
→ Scope Authorization / Organization

Dev B
→ IAM Login / Refresh / Reset
```

共享 Schema 时用小 PR 协调，不允许两边各建一套身份模型。

### W2 — M2/M3

统一 `ProviderAdapter` 契约合并后：

```text
Dev A
→ DeepSeek / OpenAI / Canonical Cost

Dev B
→ Import Engine / MiMo / Kimi / GLM / UI
```

### W3 — M4

```text
Dev A
→ BillingPeriod / Budget

Dev B
→ Expense / Approval
```

两边都依赖已经稳定的 Attribution。

### W4 — M5

Ledger Posting 触及：

```text
Period
Budget
Allocation
Expense / Charge
Audit
```

所以核心 Posting 不强行拆成两个人同时乱改。

```text
Dev A
→ Ledger Posting / Correction

Dev B
→ Ledger Query / Lineage / Frontend / Tests
```

### W5 — M6

```text
Dev A
→ Reconciliation / Close Coordinator

Dev B
→ Close Blocker / Frontend
```

### W6 — M7/M8

```text
Dev A
→ Performance / DB / Concurrency / Release Core

Dev B
→ Frontend Integration / Security / Runtime Failure / Docs

Both
→ Bug Fix / Cross-module E2E / Review
```

## 4. Critical Path

```text
M0
→ M1
→ M2
→ M3
→ M4
→ M5
→ M6
→ M7
→ M8
```

前端可以提前基于 API Contract 开发，但 V1 完成必须基于真实后端集成，不接受“Mock 页面已经做好”作为业务完成。

## 5. 禁止的伪并行

不要两个人分别造：

```text
两套 Money
两套 ProblemDetail
两套 ProviderAdapter
两套 Auth Context
两套 Pagination
```

共享契约必须先合并再并行。

## 6. Milestone 完成条件

只有当：

```text
对应 Issue 已 Merge
main CI Green
该阶段 Acceptance Test 通过
```

才算 Milestone 完成。

Feature Branch 上“已经写完”不算完成。
