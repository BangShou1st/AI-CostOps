# 2026-10-14 报销链交付复核与回归补齐

## 范围与代码身份

基线为 `codex/history-coverage-20261012@f6a494c`。本次复核保留报销链业务实现，
仅加强已有证据恢复测试、修正前端测试依赖和查询方式，并更正验收记录中的证据边界。
没有引入中间件或改动事务边界；未推送、建 PR、合并或执行付费调用。

被验证的测试源码为上述基线加以下两文件修改，执行前的补丁保存在
`.local-dev/reviews/2026-10-14-expense-followup/tested-source.patch`：

- `backend/src/test/java/com/aicostops/expense/ExpenseEvidenceBindingRecoveryIntegrationTest.java`
- `frontend/src/features/expenses/ExpensePages.test.tsx`

首轮回归日志：`.local-dev/reviews/2026-10-14-expense-followup/workspace-verify.log`。
首轮隔离 Maven 输出：`.local-dev/builds/verify-1791913329304-3da8cff3-86b1-456a-82e3-2726343a8ae5/`。
该轮执行中断，没有最终退出结果；04:41 在同一代码上重新发起完整回归，日志另存
`.local-dev/reviews/2026-10-14-expense-followup/workspace-verify-after-interruption.log`。
第二轮隔离 Maven 输出：`.local-dev/builds/verify-1791924093858-b5124480-7c96-4f3d-bd31-b50b34ad5050/`。
文档修改不影响被测代码。未跟踪的 `.freebuff/`、`CHANGELOG.md`、`LICENSE` 原样保留。

## 复核结论与更正

1. 权限先于幂等重放的顺序有源码和测试依据。但新增撤权用例直接删除权限映射并清空 Redis，
   验证的是重新加载授权后的拒绝行为，不能替代真实撤权命令的缓存失效、失败处理或多实例传播验收。
2. `MyBatisExpenseIdempotencyStore` 的重复键捕获分支存在“锁读为空则重抛”，
   但这不能推出“先到请求回滚必然进入该分支”。本次独立 MySQL 诊断验证：双请求场景中，
   先到请求回滚后，等待唯一键的后到请求成功重新预占；先到请求提交后，后到请求重放已有结果。
   原记录的必然异常断言已撤回。该防御分支本身未被复现为业务故障，完整 API 与多请求竞争仍待验证。
3. 证据绑定审计失败的原测试只断言行保留且 `AVAILABLE`；另一用例测试的是两次成功上传，
   尚不足以直接证明失败后的可恢复性。现加强同一用例：撤销故障注入后重传，
   断言复用原证据 ID、绑定版本只递增一次、成功审计仅一条、下载字节等于原合成凭证。
4. 前端 QueryClient 已配置 `retry: false`，故原记录中的“React Query 重试导致超时”归因不成立。
   修改前本次隔离运行 403 用例在默认 5 秒内通过，测试耗时 4.50 秒；它表明余量较小，
   不能单独证明耗时根因。现为每个用例重置并提供 `listTargets` 返回值，避免依赖前序用例；
   403 用例查找一次确认按钮并保留 enabled 与错误文案断言。没有放宽超时或弱化断言。
5. 先前 27 项容器/连接错误的隔离重跑只证明单独运行通过，不能据此断定整套运行无生命周期问题。
   旧记录中的“184 个 Spring 上下文”和内存压力根因缺乏直接证据，已收窄表述。

以上没有确认新的生产业务缺陷。孤儿证据保留仍是当前策略；后续回收应依据引用和保留规则设计，
不能在绑定回滚时直接删除共享对象。

## 本机环境

用户重启 Docker 后，引擎正常。`.wslconfig` 仍设置 `memory=2GB`、`processors=8`、`swap=2GB`；
Docker 引擎报告可用内存 1,995,665,408 字节（约 1.86 GiB）。现有五个容器包括本项目的
MySQL/Redis/MinIO 及其他项目的 Keycloak/PostgreSQL；本次没有停止它们或更改 WSL/Docker 配置。
第二轮开始时只有本项目三个容器运行，04:56 监测时其他项目两个容器已恢复运行；整个第二轮仍通过。

完整回归通过根入口串行运行，未同时启动三端或浏览器验收。现有共享 MySQL 与 V22/V24 各自的
静态 MySQL 容器可能在同一测试 JVM 内保留，属于待监测的资源因素；不能仅凭配置推断发生 OOM。

## 本次验证

- 修改前 403 用例隔离诊断：1 项通过，其余 25 项因名称筛选未运行；未提高默认超时。
- 修改后 `ExpensePages.test.tsx` 整文件：26 项通过，0 失败，0 跳过，测试耗时 43.48 秒。
- 首轮 `npm run verify`：后端 surefire 完成 640 项，0 failures、0 errors、1 Windows 平台跳过；
  集成测试留下 138 份 XML 报告、902 项，0 failures、0 errors、0 skipped，随后执行中断。
  日志最后写入时间为 02:00:59，04:41 复核时进程已不存在；具体中断原因未确认，不计为完整回归通过。
  加强后的证据恢复测试 4 项已在该轮通过。
- 第二轮完整 `npm run verify`：**退出码 0，所有步骤通过**（约 04:41–05:30）。测试源码 Git blob 身份保持一致：
  后端 `dabfd26e43d2b63104686001c5b5956cbaad881e`；前端 `0c17f916f4ecf93356faad9af69a986246ed9de5`。

| 第二轮阶段 | 报告结果 |
| --- | --- |
| 脚本单元 / 生命周期 | 24 / 2 项通过，无跳过 |
| 后端 surefire（87 份 XML） | 640 项，0 failures、0 errors、1 Windows 平台跳过 |
| 后端 failsafe（185 份 XML） | 1218 项，0 failures、0 errors、0 skipped |
| Gateway surefire / failsafe | 174 / 84 项，均 0 failures、0 errors、0 skipped |
| 前端全量（72 个文件） | 547 项通过，无跳过，耗时 191.23 秒 |
| 前端 lint / 生产 build | 全部通过 |

V10 10 项、V11 16 项、V22 1 项均在本次完整运行中通过；V24 7 项也通过。
报销链匹配 `*Expense*IntegrationTest` 的 85 项再次通过。403 用例保留默认 5 秒预算，未放宽断言。
后端 / Gateway Maven 分别返回 `BUILD SUCCESS`，耗时约 38:54 / 06:05 分钟。
旧错误没有在本轮复现，但旧故障的具体根因仍不能由这一次成功运行反推。

## 独立幂等并发诊断

完整回归结束后，启动独立 MySQL 8.4，应用真实 V1–V29 迁移，使用编译后的生产
`ExpenseIdempotencyMapper`、`MyBatisExpenseIdempotencyStore` 和 `ExpenseCommandTransactions`。
在默认 `REPEATABLE-READ` 下，由两线程携带相同键执行；通过
`information_schema.innodb_trx` 观察后到事务处于 `LOCK WAIT` 后，才释放先到事务。

| 先到请求结束方式 | 后到请求结果 | 持久结果 |
| --- | --- | --- |
| 回滚 | 新预占成功，`replay=false`，无重复键异常 | 1 条最终成功幂等记录，模拟副作用仅 SECOND |
| 提交 | 重放成功，`replay=true` | 1 条最终成功幂等记录，模拟副作用仅 FIRST |

两种情况均核对最终响应状态 200 及相应响应内容，诊断进程退出码 0。
该证据覆盖组件与数据库事务，不替代完整报销 API 的故障注入、多请求死锁或撤权缓存验收。
本次没有因此改变幂等实现。诊断代码与日志留在本机，未包含真实凭证或账单：

- `.local-dev/reviews/2026-10-14-expense-followup/ExpenseIdempotencyRollbackProbe.java`
- `.local-dev/reviews/2026-10-14-expense-followup/idempotency-rollback-probe.log`

本次没有产品 UI 改动，未重复既有浏览器报销验收。真实 Provider/OpenCode 联调、独立身份及浏览器环境的
组织隔离证据继续保留 DEFERRED；单管理员验收不能替代这些证据。

## 下一步

本批本机完整回归欠账已补齐；远程 CI 仍未执行。后续任务恢复与权限缓存审查应显式覆盖真实撤权入口、
缓存失效失败、旧身份重放和并发回滚恢复；不要把本批 SQL 修改加 Redis 清空的测试当成已验收。
