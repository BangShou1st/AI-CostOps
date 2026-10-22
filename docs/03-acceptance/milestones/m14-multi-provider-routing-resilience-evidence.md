# M14 Multi-provider Routing / Resilience 最终证据

## 版本与范围

- 分支：`feat/m14-multi-provider-routing-resilience`
- PR：[#146](https://github.com/BangShou1st/AI-CostOps/pull/146)
- Implementation SHA：`54b11522c0ea6a758117dc94f50b7ac1fee22ca3`（生产源码未修改）
- Evidence/test HEAD SHA：`db716e64921ea78ed04359f3ea84b6c0edcf79dd`
- Evidence/final HEAD：`db716e64921ea78ed04359f3ea84b6c0edcf79dd`（后续仅有 docs-only refresh）
- Issue：[#145](https://github.com/BangShou1st/AI-CostOps/issues/145)
- 未创建新 branch、未创建新 PR、未 merge。

本轮只增加 migration/backfill 与 streaming cancellation 的 deterministic regression evidence，并刷新本文件与 PR 描述；没有新增生产架构改动。

## V22 legacy backfill regression

测试：`V22LegacyRoutingBackfillIntegrationTest.backfillOnlyCreatesOneActiveOrgDefaultPolicyAndOneCandidateForCurrentRoute`

测试启动真实 MySQL 8.4 Testcontainer，先执行 Flyway V1–V21，插入 legacy provider/account/model/pricing fixture，再执行真实 V22 migration/backfill，最后直接查询 backfill 结果：

- expired `ACTIVE` pricing：legacy routing policy 数量为 0，不创建 `ACTIVE` policy。
- future `ACTIVE` pricing：legacy routing policy 数量为 0，不创建 `ACTIVE` policy。
- currently-effective MiMo legacy route：恰好一个 org-default `ACTIVE` policy，恰好一个 `ACTIVE` candidate，candidate 指向预期 legacy account/provider model，priority 为 0。

这不是 SQL 字符串检查，也不是最终 schema metadata 检查；断言发生在真实 migration/backfill 执行后的数据上。V1–V21 文件未修改。

## Streaming SAFE cancellation regression

测试：`GatewayStreamingSafeFailoverCancellationIntegrationTest`

测试使用 `CountDownLatch`、`AtomicBoolean`、`BooleanSupplier` cancellation seam 与 dispatch-commit callback 控制事务窗口，没有 sleep timing，也没有修改生产架构。

### Window A

`clientCancellationAfterASafeReleaseBeforeBTx2StaysNoBillable`

A 已获得 positive SAFE、durable `SAFE_NO_BILLABLE_EXECUTION`，A reservation 已 `RELEASED`；barrier 在 B 跨 TX2 前阻塞，随后触发 client cancellation。结果：

- A 仍为 `SAFE_NO_BILLABLE_EXECUTION`。
- A 为 zero Usage Fact、zero Settlement。
- A reservation 为 `RELEASED`。
- B 没有 route attempt/provider call。
- 不存在 `BILLABLE_POSSIBLE` route。
- request 收敛为 no-billable terminal `FAILED_PRE_DISPATCH`。

### Window B

`clientCancellationAfterBTx2KeepsBConservativeWithoutCFailover`

A 为 SAFE + RELEASED；barrier 在 B 已提交 TX2 / `DISPATCH_INTENT` 后、完成 provider dispatch 前触发 client cancellation。结果：

- A 仍 SAFE，zero usage。
- B 以 conservative post-dispatch semantics 收敛为 `BILLABLE_POSSIBLE`。
- B reservation 保持 `PENDING_HOLD`，没有被当成 SAFE hold 释放。
- 没有后台 retry/failover 到 C，C 没有 attempt/provider call。
- request 收敛为 `CANCELED_AFTER_DISPATCH`。

两窗口均通过真实 MySQL/Redis integration fixture 验证；本轮没有暴露生产 bug，因此没有生产修复。

## SAFE stability 与真实 concurrency 的区分

SAFE failover stability 的证据是 `GatewaySafeFailoverIntegrationTest`；它不作为 MySQL concurrency evidence。

真实 concurrency 的证据是 `GatewayFailoverConcurrencyIntegrationTest`，包含：

- two workers；
- `CountDownLatch`/barrier；
- `@RepeatedTest(5)`；
- candidate uniqueness；
- N+1 predecessor SAFE；
- `ACTIVE` / `PENDING_HOLD` rejection；
- real MySQL/Redis Testcontainers。

focused concurrency run：`13 tests, 0 failures, 0 errors, 0 skipped`。

## Migration diff

相对 `origin/main`，migration 目录 diff 仅为：

- `backend/src/main/resources/db/migration/V22__m14_multi_provider_routing.sql`

V1–V21 migration untouched；本轮新增的 V22 regression test 不修改任何 migration 文件。

## 本轮验证结果

| 范围 | 实际验证 | 结果 |
|---|---|---|
| backend migration focused | `V22LegacyRoutingBackfillIntegrationTest` | `1/1` pass；真实 MySQL 8.4；真实 V1–V22 Flyway execution |
| gateway streaming cancellation focused | `GatewayStreamingSafeFailoverCancellationIntegrationTest` | `2/2` pass；两个 deterministic windows |
| gateway concurrency focused | `GatewayFailoverConcurrencyIntegrationTest` | `13/13` pass；two workers、barrier、`@RepeatedTest(5)` |
| backend full verify | `backend\mvnw.cmd -B verify` | `523` unit（1 skipped）+ `907` integration；0 failures/errors；BUILD SUCCESS |
| gateway full verify | `gateway\mvnw.cmd -B verify` | `129` unit + `80` integration；0 failures/errors/skipped；BUILD SUCCESS |
| frontend test | `npm test -- --run --maxWorkers=1` | `48` files、`434` tests pass |
| frontend lint | `npm run lint` | pass |
| frontend build | `npm run build` | pass；仅既有 bundle size warning |
| browser E2E | hosted CI isolated Compose + Playwright | `6/6` pass |
| Docker | backend/gateway/frontend local builds | 三个 image build pass：`ai-costops-backend:m14-local`、`ai-costops-gateway:m14-local`、`ai-costops-frontend:m14-local` |
| whitespace | `git diff --check` | pass |

本地 isolated Compose browser E2E 曾因开发 bootstrap credential/rate-limit 状态漂移未通过；未将该次本地结果作为 evidence，hosted CI 的 isolated Compose `6/6` 为 browser E2E 结果。

## Hosted CI / Security

- latest hosted CI：[`33944974372`](https://github.com/BangShou1st/AI-CostOps/actions/runs/33944974372)，`db716e64921ea78ed04359f3ea84b6c0edcf79dd`，完全 green；backend/gateway unit、integration、architecture，frontend test/lint/build，browser E2E 与 Docker build 全部 pass。
- latest hosted Security：[`33944974385`](https://github.com/BangShou1st/AI-CostOps/actions/runs/33944974385)，`db716e64921ea78ed04359f3ea84b6c0edcf79dd`，完全 green；CodeQL Java/Kotlin、CodeQL JavaScript/TypeScript 与 Trivy 全部 pass。
- unresolved review threads：`0`。
- backend/gateway/frontend totals：backend `523 unit + 907 integration`；gateway `129 unit + 80 integration`；frontend `48 files / 434 tests`。
- PR #146 description 已同步本轮测试名称、真实 concurrency 语义、最新 totals 与 hosted run IDs。

## Safety / content

- provider secret、raw gateway key、完整 prompt/completion、token 未写入本证据。
- 未执行真实外网 provider call；provider adapter 行为由 mock upstream certification 覆盖。
- 本轮没有 merge，等待 Sol 最后一眼确认。
