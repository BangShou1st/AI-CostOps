# M21 运维验收修复（2026-10-19）

基线：`dev @ ab23175e53e0b23f7780c6e5f273d8598373b18c`。
范围：仅运维脚本（`scripts/m21/*.ps1`）与验收记录。**未改财务业务逻辑、Zen/Kilo 协议、`main`；未新增供应商；未部署；未使用真实收费 Provider。**

## 一、MySQL 8.4 锁定读的真实权限（修正上一轮的误判）

在**独立 MySQL 8.4.11** 实例上，以真实 Gateway 账号（非 root）实测 `SELECT ... FOR UPDATE`：

| 用例 | 授权 | 结果 |
| --- | --- | --- |
| P1 | 仅 `SELECT ON aicostops.budget` | **拒绝** `ERROR 1142 SELECT with locking clause command denied` |
| P2 | `SELECT` + `GRANT LOCK TABLES ON aicostops.*` | **通过**（返回行） |
| N1 | 同上 | `UPDATE budget` 仍 **拒绝** `ERROR 1142` |

结论：MySQL 8.4 下锁定读要求的是**数据库级 `LOCK TABLES`**，不是 `UPDATE`。上一轮记录中“`LOCK TABLES` 亦不足、需 `UPDATE`”的结论有误（当时把冗余 `UPDATE` 误当必需），已在 [2026-10-18 记录](2026-10-18-dev-financial-closed-loop-acceptance.md)中改正。

### 修复
- `provision-gateway-db.ps1`：新增 `GRANT LOCK TABLES ON <db>.* TO <gateway>@'%';`；**不授予** `budget/billing_period` 的 `UPDATE`/`DELETE`，不授予 `GRANT OPTION`。新增脚本级断言：必须存在 `LOCK TABLES`；`budget/billing_period` 授权行不得含 `UPDATE/DELETE/ALL`。
- `verify-v3-gateway-privileges.ps1`：新增 `LOCK TABLES` 存在性与 `budget/billing_period` 无 `UPDATE/DELETE/ALL` 两项 Phase-1 检查。

### 实测（真实 Gateway 账号，非 root）
`pwsh -File scripts/m21/verify-v3-gateway-privileges.ps1 -ComposeProject aicostops-m21-dev`
→ **`M21_GATEWAY_PRIVILEGE_GREEN`，20 项检查全过**，含：
`No GRANT OPTION / No ALL / No DELETE / No DROP`；`LOCK TABLES present`；`No UPDATE/DELETE on budget/billing_period`；19 张表运行时 `SELECT` 全过；4 项 `SELECT ... FOR UPDATE` 全过；Gateway 自有表 `INSERT+UPDATE` 回滚通过；11 项负向（`budget UPDATE`、`billing_period close`、`ledger_posting/entry INSERT`、`provider_credential/provider_catalog UPDATE`、`gateway_usage_fact/gateway_request DELETE`、`DDL CREATE/DROP`、`GRANT` 尝试）全部 `ERROR 1142` 拒绝。

## 二、密码传递与组织/账户关联

### 密码传递（原脚本用宿主环境变量，`compose exec` 不转发）
- `Invoke-Gateway`（`provision-gateway-db.ps1`）：原错用 root 密码；改为使用**该账号自身密码**，经 stdin 交给容器内 `read`（不落宿主命令行）。PowerShell 管道以 CRLF 结尾，`read` 会带上尾随 CR，故用 `tr -d '\r'` 去除后再作 `MYSQL_PWD`。
- `verify-v3-gateway-privileges.ps1`：`Invoke-Root` 改为读取**容器自身** `MYSQL_ROOT_PASSWORD`；`Invoke-Gateway` 同上以账号密码经 stdin 传入（含 CR 去除）。修复前该脚本“所有运行时探针报 `Access denied (using password: YES)`”即源于此。

### 组织/账户关联审核（`seed-v3-operational.ps1`）
- Step 1：`provider_account` 改为按组织 slug 关联（`JOIN organization o … AND o.slug=$OrganizationSlug`），避免选中其他组织的 MIMO 账户；新增 `-OrganizationSlug`（默认 `local-dev`）。
- Step 3：`provider_model` 改为从**本组织本账户的** `routing_policy_candidate` 解析（`org_id + provider_account_id + status='ACTIVE'`），避免选中其他组织/账户的模型（旧查询按 `provider_code` 全局取，且 `provider_account_id IS NULL`）。
- Step 5/6/7：`provider_connection_profile`、`provider_credential`、`routing_policy_candidate` 查询均补 `org_id` 约束。

实测：`seed-v3-operational.ps1` → `M21_SEED_PASS`（账户 1 / 组织 1 / 模型 1 / 策略 1 / 定价 1）。

## 三、财务闭环 Smoke 强化与实测

`invoke-v3-operational-financial-smoke.ps1` 新增断言：
- 正常：`route_attempt` 必须 `COMPLETED`，且 `provider_connection_profile_id / routing_policy_id / provider_account_id / provider_model_id / pricing_version_id` **逐项等于种子链路**；`usage_fact` 必须 `FINAL` 且 `pricing_version_id` 与 `route_attempt_id` 关联一致；`settlement` 的 `billing_period_id` 与 `pricing_version_id` 一致；**预占必须存在并最终 `FINALIZED`**（不再容忍缺预占）。
- 重复：**显式断言 HTTP 409**、Provider 不再执行、无新增入账、Budget 不再变化。
- 失败：先断言 **Mock 已装载 `http500`**，再断言失败请求**确实到达** Mock（delta=1）并由 Mock 500 触发非 200；失败请求仅记录一次、不产生 Ledger、不产生 SETTLED、Budget 不变。

实测 `M21_FINANCIAL_SMOKE_PASS`（0 断言失败），完整 ID 关联：

| 环节 | 值 |
| --- | --- |
| request | id=10，`gwr_aacba331-319d-4fcd-b7bf-379a9eec464b`，period=1 |
| route_attempt | id=9，COMPLETED，profile=policy=account=model=pricing=1 |
| usage_fact | id=5，FINAL，pricing=1，route_attempt=9；维度 INPUT 5 / OUTPUT 3（PROVIDER_FINAL）、REQUEST 1（GATEWAY_DETERMINISTIC） |
| settlement | id=3，SETTLED，calculated=0.000430000000000000，posted=**0.00043000**，pricing=1，period=1，currency=USD |
| ledger | posting id=3（GATEWAY_SETTLEMENT，SYSTEM）、entry id=3（COST=0.00043000，USD） |
| budget | id=1，actual 增量 = 0.00043000 |
| reservation | id=7，**FINALIZED** |

场景：正常 **PASS**；重复 **PASS**（409、无二次入账）；失败 **PASS**（Mock 500 → 502，无零成本入账）。

## 四、`npm run verify` 的 Hikari 等待问题（本机调查）

### 证据
- 带 `spring.datasource.hikari.leak-detection-threshold=60000` 完整跑一次 backend `verify`：**1264 tests / 0 failures / 3 errors**，总耗时 **39:02**（不是死锁，会跑完）。3 个 error 均为**连接/容器级**，非断言失败：
  - `V22LegacyRoutingBackfillIntegrationTest ? ExceptionInInitializer`（Testcontainers 建容器 `Status 500 Could not start container`）；
  - `V11MigrationIntegrationTest.setUp CannotGetJdbcConnection`、`tearDown Communications link failure`。
- Hikari 泄漏告警 **2 条**，栈均指向**合法但耗时很长**的夹具批量写：
  - `M8SchemaQueryExplainIntegrationTest.insertBudgets(:340)` ← `setUp(:62)` 一次 `insertBudgets(10_000)`；
  - `M9ReportingScaleBenchmarkIntegrationTest.seedReportingFixture(:268)` ← `batchUpdate` 批量 `raw_provider_record`。
- 同期单测耗时：`M8SchemaQueryExplainIntegrationTest` 1 项 **120.4s**、`M9ImportScaleBenchmarkIntegrationTest` 82.4s。
- 测试 Hikari 配置为 `maximum-pool-size: 2`（`backend/src/test/resources/application-test-defaults.yml`）。
- 上述 3 个失败类**单独重跑全部通过**：`V11` 16 项 + `V22` 1 项，`Tests run: 17, Failures: 0, Errors: 0`，`BUILD SUCCESS`。

### 区分三种可能
| 假设 | 判定 | 依据 |
| --- | --- | --- |
| 连接泄漏（连接未归还） | **否** | Hikari 告警栈落在**已完成批量写入并会归还**的夹具（10k 行 insert / 批量 seed）；无“永不归还”的证据；同批测试单独重跑通过 |
| 测试清理锁竞争（InnoDB 等锁） | **否** | 无 `innodb_lock_wait_timeout`/锁等待错误；被观察的阻塞栈是 Hikari `ConcurrentBag.borrow → HikariPool.getConnection`（等连接），错误是 `CannotGetJdbcConnection` / `Communications link failure`（取连接/链路），不是锁 |
| 本机资源竞争 | **是（主因）** | Docker 建容器返回 500、通信链路失败、连接被关闭，且失败类**单独重跑即通过**；与既往记录“本机三端启动窗口与 V10/V11 JDBC 超时重合”一致 |

### 结构性脆弱（非环境单因）
测试连接池 `maximum-pool-size: 2` 与 80–120s 的基准/EXPLAIN 夹具（一次插入 10k 行、批量 seed）叠加，在宿主存储延迟放大时会使连接被长时间占用，其他线程/清理在 Hikari 取连接处排队。**建议（本任务未改，避免无谓改动/放宽）**：如需稳定本地长跑，可评估将测试池容量小幅上调或把超长基准夹具的种子置于事务外并复用连接；CI（GitHub Runner）上 backend `verify` 通过，说明套件本身无逻辑缺陷。**未删除测试、未放松断言。**

## 五、本提交在 GitHub Actions 的结果

| 工作流 | 提交 | 结论 | 链接 |
| --- | --- | --- | --- |
| CI | `ab23175` | **success** | https://github.com/BangShou1st/AI-CostOps/actions/runs/39144608718 |
| Security | `ab23175` | **success** | https://github.com/BangShou1st/AI-CostOps/actions/runs/39144608670 |

## 六、验证范围与退出码

| 验证 | 结果 |
| --- | --- |
| 独立 MySQL 8.4.11 权限矩阵（真实 Gateway 账号） | `SELECT only` 拒绝 / `+LOCK TABLES` 通过 / `UPDATE` 拒绝 |
| `verify-v3-gateway-privileges.ps1` | `M21_GATEWAY_PRIVILEGE_GREEN`（20 检查） |
| `provision-gateway-db.ps1` | `M21_GATEWAY_DB_PROVISION_PASS` |
| `seed-v3-operational.ps1` | `M21_SEED_PASS` |
| 财务闭环 smoke（正常/重复/失败） | `M21_FINANCIAL_SMOKE_PASS`（0 断言失败） |
| backend `verify`（本机，带泄漏检测） | 1264 tests / 0 failures / 3 errors（连接/容器级，非断言）；失败类单独重跑 `Tests run: 17, 0 failures, 0 errors`，`BUILD SUCCESS` |

## 七、观察（未在本任务处理）

以“仅含本脚本授权”的干净 Gateway 账号运行时，Gateway 的 Advisor 推理 worker 会因缺少 `advisor_inference_job/attempt` 写权限而记错误日志（不影响请求与财务链路）。冻结的 M21 权限契约未包含 Advisor 相关表；是否纳入属契约变更，未在本任务擅自扩大授权。

## 八、约束遵守

未改 `main`、未合并/部署/发布；未改财务业务逻辑与 Zen/Kilo 协议；未新增供应商；未使用真实收费 Provider；独立 Compose 项目与独立数据卷；未提交 `.freebuff/`、`.trae/`、真实凭据或临时数据；满足“不授予 Gateway `budget/billing_period` 的 `UPDATE/DELETE/GRANT OPTION`”。
