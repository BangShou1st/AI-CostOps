# M7 — Workbench & End-to-End Integration 实施计划

> Baseline: `main@dccf34b` (M6 merged, PR #90)
> Scope: AIC-060 ~ AIC-065。单人串行执行，按依赖序合并为 3 个 PR。

## 1. 范围与依赖

```text
AIC-060 Reporting Read Model      ──┐
AIC-061 Redis Dashboard Cache     ──┼──> PR1 feat/m7-reporting-workbench（后端+缓存+UI）
AIC-062 Workbench React           ──┘
AIC-063 Provider 主链路 E2E        ──┐
AIC-064 Expense 主链路 E2E         ──┴──> PR2 test/m7-e2e-mainlines
AIC-065 Audit 查询                 ────> PR3 feat/m7-audit-query
```

## 2. 冻结的架构事实

- 新模块 `com.aicostops.reporting`，只读（01-module-boundaries.md §reporting）：
  允许 MySQL Read Query + Redis Cache；禁止 Financial Mutation Repository；
  依赖方向 reporting → ledger/cost/budget 的**读侧**，ArchUnit 白名单需同步。
- 无新表、无 Flyway migration：全部聚合查询落在现有表
  （charge_fact / allocation_line / budget / expense_claim / duplicate_candidate /
  reconciliation_run / billing_period / import_batch）。
- 权限复用现有 READ 集，不新增权限 seed：
  卡片按调用者权限逐段填充（无对应 READ 权限 → 该 section 缺省省略）。
- Workbench 聚合端点要求认证即可访问；各 section 权限见 §4。

## 3. API 契约（openapi.yaml + 契约测试）

```text
GET /api/v1/workbench?billingPeriodId=
  → 200 WorkbenchResponse（按 currency 分组；section 按权限裁剪）
  → 401/403 ProblemDetail
```

WorkbenchResponse sections：

| section | 内容 | 需要权限 |
|---|---|---|
| currentPeriod | periodId/range/status | PERIOD_READ |
| costByProvider | provider_code × currency → sum | COST_READ |
| costByProject | project × currency → sum | COST_READ |
| budgetVariance | scope × currency: total/actual/committed/available | BUDGET_READ |
| unallocatedCharges | count + amount by currency | ALLOCATION_READ |
| duplicateCandidates | OPEN/SUSPECTED count | DUPLICATE_REVIEW |
| pendingApprovals | SUBMITTED/NEEDS_INFO count | EXPENSE_REVIEW |
| openReconciliations | material OPEN run count | RECONCILIATION_READ |
| closeStatus | 当前 period CLOSING/CLOSED 状态 | PERIOD_READ |

规则：金额一律 DECIMAL(20,8) 字符串按 currency 分组，禁止跨币种求和（验收矩阵 No Cross-currency Sum）。契约测试新增 `M7OpenApiContractTest` 锁 op/status 集。

## 4. 后端结构（PR1）

```text
com.aicostops.reporting/
├── api/WorkbenchController            # GET /workbench，认证 + section 裁剪
├── application/
│   ├── WorkbenchQueryService          # 编排各 section read query
│   ├── WorkbenchReadModels            # record DTO
│   └── DashboardCachePort             # cache-aside 接口（application 定义）
└── infrastructure/
    ├── WorkbenchQueryMapper           # @Select 只读聚合 SQL
    └── RedisDashboardCacheAdapter     # TTL 60s，Redis 故障 fallback MySQL 直查
```

- Cache key：`workbench:{orgId}:{periodId|current}`，value 为 JSON，TTL 60s；
  写路径不变、无失效钩子（stale bound ≤ TTL，符合 AIC-061 Short TTL）。
- Redis Down 测试：容器停 Redis → 查询仍成功且走 MySQL（fallback），恢复后回写。
- EXPLAIN Review 记录进验收证据（AIC-066 复用）。

## 5. 前端结构（PR1）

```text
frontend/src/features/workbench/
├── WorkbenchPage.tsx        # /workbench，卡片优先于图表（AIC-062）
├── api/workbenchApi.ts      # TanStack Query，staleTime 30s
├── api/workbenchTypes.ts
├── components/...           # 六卡片：Unallocated/Duplicate/PendingApproval/
│                            # OpenReconciliation/BudgetOverrun/CloseStatus
└── presentation.ts          # money/currency 展示格式化（复用 lib/money.ts）
```

- `/app` landing 在有任一 section 权限时优先跳 `/workbench`。
- 无 Chart 库新依赖；ECharts 已在技术栈内但 V1 仅在 Budget Variance 用简单条形。

## 6. E2E 主链路（PR2）

后端 Testcontainers 全链路集成测试（无浏览器）：

```text
E2eProviderMainlineIntegrationTest:
login(FINANCE_ADMIN) → upload synthetic fixture(MinIO) → import run
→ confirm → allocate(confirm) → ledger post → reconcile(run+resolve)
→ close(blockers PASS → CLOSED)

E2eExpenseMainlineIntegrationTest:
employee login → evidence attach → submit → finance approve
→ allocate → post → close
```

断言每步状态机迁移 + 终态账目恒等式（posted = allocated sum；closed 后写拒绝）。

## 7. Audit 查询（PR3）

```text
GET /api/v1/audit-events?orgId&eventType&from&to&page
→ AUDIT_READ @ ORG；只读 audit_event；敏感动作清单核对文档化
```

## 8. 任务序列（TDD RED→GREEN）

PR1：T1 契约+controller 骨架 → T2 各 section mapper SQL+单测 → T3 service 编排+权限裁剪测试 → T4 cache port+redis adapter+故障注入测试 → T5 前端 types/api/page+vitest → T6 landing 改造 → T7 ArchUnit/openapi/契约测试 → T8 全量回归。
PR2：T9 Provider E2E → T10 Expense E2E。
PR3：T11 audit query API+权限测试 → T12 敏感动作覆盖矩阵文档。

## 9. 完成标准

每个 PR：本地三组全绿（unit/architecture/integration）+ CI 绿 + squash merge main。
M7 关闭 = 三 PR 合并 + 验收证据记录（两条主链路 E2E 报告、EXPLAIN 记录）。
