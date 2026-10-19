# M21 补充验收：Advisor Worker 最小数据库权限

日期：2026-10-19

基线：`d72d9848ebd17b442ecee3e9c0d4644c620a460a`
范围：仅补齐运行中的 `AdvisorInferenceWorker` 数据库权限。M18/M21 已冻结验收记录保持原样。

## 结论

独立 MySQL 8.4.11 Compose 项目以真实 Gateway 数据库账号 `gw_m21` 复现了 Worker 的缺权：周期任务在孤儿认领恢复阶段对 `advisor_inference_job` 执行 `UPDATE` 时得到 MySQL `ERROR 1142 (42000): UPDATE command denied`，每个 30 秒周期记录 `Advisor worker tick failed`，因此认领和执行均无法继续。对 Worker 的锁定查询、Attempt 更新、Explanation 插入、Profile 和 Evidence 查询做非 root 探针，也分别得到缺少 `SELECT`、`UPDATE`、`INSERT`、`SELECT` 的 1142 错误。

补充五张 Advisor 表的精确表级授权后，真实 Worker 完成成功和上游失败两种任务；无重复调用、重试或不一致终态。授权不包含财务事实表写权限。

## Worker SQL 权限矩阵

下表覆盖 `AdvisorInferenceWorker` 运行时实际调用的 `AdvisorJobMapper` SQL。`AdvisorInferenceWorker.java` 的 `tick`、认领、执行、终态及卡住任务收敛逻辑见 93–118、127–192、194–213、217–269、307–319、374–425 行。

| 表 | Worker 实际 SQL / 操作 | `FOR UPDATE` | 最小表级权限 | 源码位置 |
|---|---|---:|---|---|
| `advisor_inference_job` | 领取 PENDING/过期 CLAIMED Job；按 org/id 读取已认领 Job；认领恢复、claim token 与 lease 更新；关联 Gateway request；成功/失败终态；扫描并收敛过期的已关联任务 | 是，`ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED` | `SELECT, UPDATE`；MySQL 8.4 所需数据库级 `LOCK TABLES` 沿用基线已有授权 | `AdvisorJobMapper.java:18–81` |
| `advisor_inference_attempt` | 关联 Gateway request 并置 RUNNING；置 COMPLETED；失败时置 FAILED 并保存安全错误码 | 否 | `SELECT, UPDATE`。更新语句在 `WHERE` 中读取 `job_id`、`attempt_no`、`status`；仅 UPDATE 探针因 WHERE 列缺少 SELECT 而返回 `ERROR 1143`，因此按现有表级授权契约给表级 SELECT | `AdvisorJobMapper.java:48–57` |
| `advisor_explanation` | 成功后与 Job/Attempt 完成状态在同一短事务中插入解释 | 否 | `INSERT` | `AdvisorJobMapper.java:168–198` |
| `advisor_profile` | 按已绑定 profile id + org 查询冻结版本；旧任务按 org + profile version 查询；凭据关联 SQL 也按 org 约束 join 此表 | 否 | `SELECT` | `AdvisorJobMapper.java:93–106,143–153` |
| `advisor_evidence_snapshot` | 按 Job id + org 读取不可变证据快照并验证 fingerprint，用于生成 Provider prompt | 否 | `SELECT` | `AdvisorJobMapper.java:119–124`；Worker 使用见 `AdvisorInferenceWorker.java:263–284` |
| `gateway_credential` | 按 org 与绑定 profile 查询内部凭据；旧任务使用受限兼容查询 | 否 | `SELECT`，已由 M21 基线授予；本补丁未增加 | `AdvisorJobMapper.java:108–117,129–153` |
| `service_identity` | 旧任务兼容查询以及绑定内部凭据的 org 约束 join | 否 | `SELECT`，已由 M21 基线授予；本补丁未增加 | `AdvisorJobMapper.java:129–153` |
| `provider_model` | 从 profile 的 provider model id 解析逻辑模型 id | 否 | `SELECT`，已由 M21 基线授予；本补丁未增加 | `AdvisorJobMapper.java:126–127` |
| `gateway_request` | 收敛已关联但超时的 Job 时读取 Gateway request state | 否 | `SELECT`，已由 M21 基线授予；本补丁未增加 | `AdvisorJobMapper.java:83–84` |

`AdvisorJobMapper` 还保留 `findActiveProfile`、subject 查询（`cost_anomaly`、`cost_forecast_snapshot`、`savings_recommendation`）和预算快照查询（`budget`、`billing_period`、`budget_reservation`）。当前 Worker 调用路径不调用这些 Mapper 方法；证据 prompt 直接来自 `advisor_evidence_snapshot`。它们没有因此获得任何新增权限。

Worker 随后调用既有 Gateway 编排服务完成受预算准入的普通请求。该请求继续使用 M21 已验收的 Gateway 表权限；本补丁不扩大这些表的权限。

## 新增 GRANT 与财务边界

`scripts/m21/provision-gateway-db.ps1` 只增加以下五条表级授权：

```sql
GRANT SELECT, UPDATE ON <database>.advisor_inference_job TO 'gw_m21'@'%';
GRANT SELECT, UPDATE ON <database>.advisor_inference_attempt TO 'gw_m21'@'%';
GRANT INSERT ON <database>.advisor_explanation TO 'gw_m21'@'%';
GRANT SELECT ON <database>.advisor_profile TO 'gw_m21'@'%';
GRANT SELECT ON <database>.advisor_evidence_snapshot TO 'gw_m21'@'%';
```

既有 MySQL 8.4 数据库级 `LOCK TABLES` 不变；没有授予 `ALL PRIVILEGES`、`GRANT OPTION`、`DROP`、`DELETE` 或无关写权限。`budget`、`billing_period`、`ledger_posting`、`ledger_entry` 没有新增写权限。原 11 项负向财务/安全断言未删除或放宽。

## 验收证据

### 修复前

- 新建独立 Compose 项目 `aicostops-advisor-worker-fix`、独立网络和数据卷；数据库 MySQL 8.4.11。连接与探针使用实际应用账号 `gw_m21`，不是 root。
- 无 Advisor grants 时，Worker 日志每 30 秒出现 `Advisor worker tick failed` 和 `UPDATE command denied ... for table 'advisor_inference_job'`；10 分钟窗口观察到 5 次。触发 SQL 是 `reclaimOrphans` 的过期 CLAIMED Job 恢复 UPDATE。
- 同一非 root 账号对 Job locking SELECT、Job/Attempt UPDATE、Explanation INSERT、Profile SELECT、Evidence SELECT 的直接探针分别复现权限拒绝。Attempt 仅有 UPDATE 时，MySQL 返回 `ERROR 1143`，指出 UPDATE 条件列 `org_id` 的 SELECT 权限缺失；表级 `SELECT, UPDATE` 后更新通过。

### 修复后真实账号权限矩阵

执行 `scripts/m21/verify-v3-gateway-privileges.ps1`：`M21_GATEWAY_PRIVILEGE_GREEN`，28 项通过。其中覆盖精确 Advisor grants、22 张基线/Advisor 表的 SELECT、5 项 `SELECT ... FOR UPDATE`、Job/Attempt 更新权限、Explanation 插入到预期 FK fence，以及原 11 项负向断言全部拒绝。无行 UPDATE 探针不改业务数据。

授权脚本本身执行结果：`M21_GATEWAY_DB_PROVISION_PASS`。

### Worker Mock 成功/失败路径

使用独立 Compose 项目中的 Gateway、Backend 与可控 Mock Provider；Mock 返回固定 Advisor 文本或 HTTP 500，不请求收费 Provider。通过 Backend API 创建 Job，实际周期 Worker 使用 `gw_m21` 执行：

- 成功：Job 与 Attempt 均为 `COMPLETED`，写入校验过的 Explanation，生成一条 Gateway request，Mock 只收到一次请求。
- 上游 HTTP 500：Job 与 Attempt 均为 `FAILED`，安全错误码为 `PROVIDER_UNAVAILABLE`，不写 Explanation；Mock 只收到一次请求。
- 再等待完整 Worker 周期后两任务仍为终态，Mock 总请求数仍为 2；没有因数据库授权失败重试或留下拆分终态。

结果：`M21_ADVISOR_WORKER_SMOKE_PASS`。新增可复用场景脚本：`scripts/m21/invoke-v3-operational-advisor-smoke.ps1`；Mock 的 `advisor-success` 模式由 `scripts/m16/mock-provider/mock_provider.py` 提供。

### 财务与组织边界

同一独立栈上的既有财务 Smoke 返回 `M21_FINANCIAL_SMOKE_PASS`，断言失败 0：普通请求完成 FINAL Usage、Settlement、Ledger 和 Budget Actual；重复请求不重复写账；上游 HTTP 500 不落账。权限矩阵保留 Gateway 对预算、账期、Ledger 财务真相的写入拒绝。

Job 查询与 fencing 仍按现有 org、claim token、状态条件进行：profile/evidence 按 org 读取；terminal update 必须匹配 claim token 与允许状态；Gateway credential/service identity join 带 org 条件。本补丁未改这些业务 SQL 或跨组织边界。

## 自动化测试

- Gateway `AdvisorInferenceWorkerTest`：12 tests，0 failures/errors/skipped。
- Backend 定向 Advisor 集成：`AdvisorTerminalConvergenceIntegrationTest`、`AdvisorScopeBudgetExecutionIntegrationTest`、`AdvisorProfileVersionExecutionIntegrationTest`：15 tests，0 failures/errors。
- 完整 `npm run verify`：真实退出码 `0`。脚本测试 27 项 + 生命周期 2 项通过；Backend Surefire 692、Failsafe 1264；Gateway Surefire 227、Failsafe 98；前端 Vitest 72 个文件 / 547 tests、ESLint 和 Vite production build 通过。
- 完整 verify 中 `GatewayApplicationTest` 按测试设计在无外部依赖的上下文启动。其后台定时 Worker 尝试 `reclaimOrphans` 时记录一条 `Access denied for user 'aicostops'@'localhost'`；该测试的 4 个断言通过，Gateway 全量 Surefire/Failsafe 也通过。该日志来自没有 Testcontainers 数据库注入的启动上下文，不是实际 `gw_m21` 权限验证；独立 MySQL 8.4 Worker Mock 路径已使用真实 `gw_m21` 账号另行通过。
- GitHub Actions CI / Security：提交推送后以该提交对应的远端实际运行结果为准，见任务最终报告。

## 历史记录和版本

本文件是对 M21 基线后发现的 Worker 运行权限缺口的补充。`m18-backend-contract-freeze.md` 和既有 M21 冻结验收记录未被改写。
