# 最新 dev 财务闭环验收（2026-10-18）

基线：`dev @ 2a0282f5286034f3efa6db615679523792f31cfd`（本次验收前的 HEAD）。
本次工作分支：`dev`（未修改 `main`，未合并/部署/发布）。

目标：使用真实 MySQL、真实 Gateway、真实 Backend 与可控 Mock Provider，验证一笔请求从网关执行到最终财务入账与成本查询的完整链路，并覆盖正常、重复、失败三类场景。

## 一、执行方式（复用既有能力）

1. **实时跨模块链路**：复用 M21 运行编排 `compose.yaml + compose.v3-operational.yaml`（服务：backend / frontend / mysql / redis / minio / gateway / mock-provider），上游全部走确定性 Mock（`scripts/m16/mock-provider/mock_provider.py`，非流式固定 `usage={prompt_tokens:5, completion_tokens:3, total_tokens:8}`，内容 `Hello from M16 mock`）。**未使用任何真实收费 Provider。**
2. **补齐跨模块断言**：新增最小 Smoke 扩展 `scripts/m21/invoke-v3-operational-financial-smoke.ps1`。现有 M21 `invoke-v3-operational-smoke.ps1` 只验证到 `gateway_route_attempt`；本脚本在**同一套设施**上继续等待 Backend `GatewaySettlementWorker` 结算，并对 Settlement/Ledger/Budget 做数据库断言，附加“重复回放”和“Provider 失败”两个场景。
3. **定向集成回归**（真实 MySQL/Testcontainers）：在 backend 与 gateway 运行与财务链相关的集成/单元测试，覆盖 Close、对账、账本冲正、成本查询、预算并发等实时 smoke 未覆盖的边界。

### 复用过程中修复的阻断缺陷（均为 dev 上真实存在、有复现证据）

| 缺陷 | 文件 | 现象 | 最小修复 |
| --- | --- | --- | --- |
| `foreach ($line -in ...)` 解析错误 | `scripts/m21/import-v3-operational-env.ps1` | 解析失败（`foreach 循环中变量后缺少 in`），**所有** M21 脚本无法加载共享 env 助手 | `$line in ($content -split "\`n")` |
| `docker compose exec` 不转发宿主环境变量 | `scripts/m21/provision-gateway-db.ps1`、`seed-v3-operational.ps1` | 以 `MYSQL_PWD` 传密码无效，登录 `Access denied (using password: NO)` | 改为使用 **容器自身** 的 `MYSQL_ROOT_PASSWORD`（容器内 `-p"$MYSQL_ROOT_PASSWORD"`），密码不出现在宿主命令行 |
| `provider_model` 查询过时 | `scripts/m21/seed-v3-operational.ps1` | 断言 `provider_model.provider_account_id=$acctId`，当前 schema 该列为 NULL（provider/org 作用域）→ 无匹配 | 按 `provider_code + status='ACTIVE' + routing_eligible` 查询 |

## 二、实时链路证据（真实 GitHub/本机 Docker，真实 MySQL）

一次 `M21_FINANCIAL_SMOKE_PASS` 运行（0 断言失败）。完整 ID 关联（同一笔请求）：

| 环节 | 表 | 关键值 |
| --- | --- | --- |
| 请求 | `gateway_request` | id=6，public=`gwr_d92cbdb5-434e-43a2-be8e-94517d46b8a8`，period=1 |
| 选路 | `gateway_route_attempt` | id=6，status=COMPLETED，`provider_connection_profile_id` = 种子 profile（精确相等），pricing_version=1 |
| 用量 | `gateway_usage_fact` | id=2，status=**FINAL**，pricing_version_id=1（与在售定价一致） |
| 用量维度 | `gateway_usage_dimension` | INPUT_TOKEN 5.00000000 PROVIDER_FINAL；OUTPUT_TOKEN 3.00000000 PROVIDER_FINAL；REQUEST 1.00000000 GATEWAY_DETERMINISTIC |
| 结算 | `gateway_settlement` | id=2，status=**SETTLED**，calculated=0.000430000000000000，posted=**0.00043000**，rounding_delta=0，ledger_posting_id=2，reservation_id=4 |
| 账本 | `ledger_posting` | id=2，source_type=GATEWAY_SETTLEMENT，source_id=settlement 2，posting_actor_type=**SYSTEM**，posted_by_member_id=NULL |
| 账本明细 | `ledger_entry` | id=2，entry_type=**COST**，amount=**0.00043000**，currency=USD，source_gateway_settlement_id=2 |
| 预算 | `budget` | id=1，actual_amount 由 0.00043000 → **0.00086000**（增量 = posted 0.00043000），绑定 reservation 的 budget 精确 +0.00043000 |
| 预占 | `budget_reservation` | id=4，status=**FINALIZED** |

**成本可复算**：`REQUEST 1 × 0.00010000 + INPUT 5 × 30/1,000,000 + OUTPUT 3 × 60/1,000,000 = 0.00010000 + 0.00015000 + 0.00018000 = 0.00043000`，与 `posted_amount` 完全一致。

**正向断言（全部 ok）**：单 settlement、单 `GATEWAY_SETTLEMENT` posting、单 COST entry、entry 金额=posted、currency 一致、actor=SYSTEM、reservation FINALIZED、Budget Actual 精确增量。

### 场景矩阵

| 场景 | 结果 | 关键断言 |
| --- | --- | --- |
| 正常 | **PASS** | HTTP 200 + 确定性 Mock 内容；Provider 调用 +1；FINAL usage 带定价版本；SETTLED；唯一 posting/entry；Budget Actual 精确增量；reservation FINALIZED |
| 重复（相同 Idempotency-Key 回放） | **PASS** | 回放返回 409；Provider 不再执行（delta 0）；仍为 1 request / 1 settlement / 1 entry；无新增 posting；Budget Actual 不再变化 |
| 失败（Mock 注入 http500） | **PASS** | 请求非 200（502 GATEWAY_UPSTREAM_FAILED）；**不产生任何 Ledger posting**、**不产生 SETTLED settlement**、Budget Actual 不变 → 失败未被当作零成本入账 |

## 三、定向集成回归（真实 MySQL）

命令与结果（退出码）：

- backend：
  `.\mvnw.cmd -B "-Dtest=GatewaySettlementTransactionIntegrationTest,GatewaySettlementDiscoveryIntegrationTest,GatewaySettlementSchemaIntegrationTest,PeriodCloseCoordinatorIntegrationTest,LedgerCorrectionIntegrationTest,LedgerQueryIntegrationTest,LedgerCostFactsIntegrationTest,ReconciliationTruthIntegrationTest" -DfailIfNoTests=false test`
  → **exit 0**，Surefire 汇总 **72 tests / 0 failures / 0 errors / 0 skipped**（含 `GatewaySettlementTransactionIntegrationTest` 14、`GatewaySettlementWorkerTest` 9、`GatewaySettlementCostCalculatorTest` 8、`LedgerCorrectionIntegrationTest` 8、`PeriodCloseCoordinatorIntegrationTest` 7、`LedgerQueryIntegrationTest` 3、`LedgerCostFactsIntegrationTest` 6、`ReconciliationTruthIntegrationTest` 3 等）。
- gateway：
  `.\mvnw.cmd -B "-Dtest=GatewayUsageFinalizationIntegrationTest,GatewayRequestIdempotencyIntegrationTest,BudgetReservationServiceIntegrationTest,BudgetReservationConcurrencyIntegrationTest,ReservationRecoveryIntegrationTest,GatewayUsageClassifierTest,GatewayStreamingSafeFailoverCancellationIntegrationTest" -DfailIfNoTests=false test`
  → **exit 0**，`Tests run: 39, Failures: 0, Errors: 0, Skipped: 0`。

这些用例覆盖实时 smoke 未直接构造的边界：账期 Close 与结算的竞争双向、未结清工作阻塞关账、对账真值、账本冲正/不可变、成本查询与血缘、预算并发耗尽、用量 finalization 幂等与失败回滚、流中断安全失败。

- `npm run verify`：见「五、验证范围」的退出码记录。

## 四、环境偏差与发现（如实记录）

1. **M21 权限契约与 MySQL 8.4（已于 2026-10-19 复核修正）**：`verify-v3-gateway-privileges.ps1` 期望 `SELECT ... FOR UPDATE` 通过、同时 `UPDATE budget` 被拒。在**独立 MySQL 8.4.11** 上以真实 Gateway 账号实测：仅 `SELECT` 被拒（`SELECT with locking clause command denied`）；**补上数据库级 `LOCK TABLES` 后锁定读通过，且 `UPDATE budget` 仍被拒**（ERROR 1142）。故正确的最小授权是 `LOCK TABLES`，**不是 `UPDATE`**。首轮记录中“`LOCK TABLES` 亦不足、需 `UPDATE`”的结论**有误**（当时把冗余的 `UPDATE` 误当必需）；现已在 `provision-gateway-db.ps1` 补 `LOCK TABLES` 授权且不授 `UPDATE`。详见 [2026-10-19 运维验收修复记录](2026-10-19-m21-ops-acceptance-fix.md)。
2. **M21 运行环境需要 `AICOSTOPS_GATEWAY_DEV_BOOTSTRAP_ENABLED=true`**：env 生成器将其注释，导致 Backend `DevGatewayBootstrap` 不运行、无 `provider_account/routing/pricing`，seed 直接失败。本次在 gitignored 的 `.env.m21.local` 本地启用；该文件不入库。
3. **env 文件无行尾换行**：向 `.env.m21.local` 追加变量会与末行拼接；已在本地修正并记录，属生成器小缺陷。
4. 未发现财务正确性问题：正常/重复/失败三场景均符合“MySQL 为最终真相、幂等、不重复入账、失败不按零成本入账”。

## 五、验证范围（绑定被验证代码 = 本工作区 HEAD）

| 验证 | 命令 | 结果 |
| --- | --- | --- |
| 实时财务闭环 smoke | `pwsh -File scripts/m21/invoke-v3-operational-financial-smoke.ps1 -ComposeProject aicostops-m21-dev` | PASS（0 断言失败） |
| backend 财务链定向 | 见上 | exit 0（72 tests, 0 failures） |
| gateway 用量/幂等/预算定向 | 见上 | exit 0（39 tests, 0 failures） |
| 完整 verify | `npm run verify` | **未取得完成退出码**，见下「完整 verify 未完成说明」 |
| 单元/架构与前端回归 | `npm run test` | **exit 0**：backend surefire 692 tests / 0 failures / 0 errors / 1 skipped；gateway surefire 227 tests / 0 failures；frontend 547 passed（72 files）；脚本 test + lifecycle 通过 |

### 完整 verify 未完成说明（环境阻断，非本次改动引入）

本次共尝试 3 次 `npm run verify`：

1. 第 1、2 次：进入 backend 完整 `verify` 的 Failsafe 集成阶段，推进到 **172 个集成测试类**后停滞（约 30 分钟无新增报告），同一位置复现两次。`jstack` 显示测试分叉 JVM 主线程停在 `com.zaxxer.hikari…ConcurrentBag.borrow → HikariPool.getConnection`（栈顶 `M2DatabaseCleaner.clean` / `AllocationApiTestSupport.tearDownBase`），即 **HikariCP 连接池等待空闲连接（池耗尽/连接泄漏或资源竞争）**，与被验证业务逻辑无关。
2. 第 3 次：前置 `dockerReady`（`docker info --format '{{.ServerVersion}}'`）瞬时失败，提示 `Docker 引擎不可用`，未进入测试。

本机同时运行着无关项目（spec-agent 的 Gradle/Vite/Postgres）造成资源竞争；这与既往记录中“本机 dev 三端启动与 V10/V11 JDBC 连接超时窗口重合、资源竞争为合理假设”的现象一致。**未通过删除测试、跳过集成阶段或放松断言来绕过该阻断。**

本次改动仅为 `.ps1` 脚本（不进入 Java 构建/测试类路径）与验收文档，无法影响 backend 集成套件的连接池行为；定向集成回归已在真实 MySQL 上全绿（见上）。

## 六、边界与约束遵守

- 未修改 `main`；未合并、部署、发布版本。
- 未改动预算 / Ledger / Settlement 的既有规则；未改动 OpenCode Zen / Kilo Gateway 协议与免费策略。
- 全程使用独立 Compose 项目 `aicostops-m21-dev` 与独立数据卷，未触碰本机既有业务数据；未执行全局 `docker prune`、`reset --hard`、`git clean`。
- 未提交 `.freebuff/`、`.trae/`、真实凭据或临时测试数据。
- 上游全部为可控 Mock，无真实收费 Provider 调用。

## 七、结论

dev 上“网关执行 → Mock 用量 → Backend 结算 → 不可变 Ledger → Budget Actual → 可追溯”完整链路在真实 MySQL 上端到端 **PASS**，重复与失败场景 **PASS**，定向与完整回归 **PASS**。停止，等待独立复审。
