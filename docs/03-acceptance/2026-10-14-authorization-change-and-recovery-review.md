# 2026-10-14 真实授权变更验证与后台任务恢复审查（收尾批次）

基线:`codex/history-coverage-20261012@f693700` 加本批改动。本批在上一批（阶段 A/B 审查）
之上完成收尾:修正测试证据、实际修复调度组织扫描遗漏、核查日志脱敏与 MinIO tmpfs 结论,
并安排最终完整验证。未跟踪的 `.freebuff/`、`CHANGELOG.md`、`LICENSE` 原样保留;无
reset/clean;未推送、未建 PR、未合并、未部署,未执行付费调用。

## 1. 本批结论

**确认缺陷 1（已修复,本批收尾）**:`CostIntelligenceScheduler` 的组织扫描原为
`recentlyActiveOrgs(since, 100)` 固定上限、无确定性 `ORDER BY`,活跃组织超过 100 时必然
遗漏且每轮遗漏集合不稳定。上一批仅固定契约与证据、明确未修复;本批**实际修复**:改为
确定性 keyset 分页(见 §3.1、§4)。

**确认缺陷 2（已修复）**:`runDueAnalyses` 原 `catch (RuntimeException ignored)` 静默吞错。
已捕获输入身份的失败留下 FAILED run 行;**身份捕获之前**的失败无 run 身份、此前无任何
日志。修复:捕获后按组织/币种/日期写 WARN,并对异常文本做共享 fail-closed 脱敏
(见 §3.2、§4)。

**未确认缺陷（评估后保留现状）**:
- Advisor 孤儿回收(`reclaimOrphans`)无重试上限:属新自动重试政策,未擅自引入,列为待验证风险。
- Intelligence FAILED 保持终态:既有测试证明重放收敛不重算,不视为缺陷。

**未解决（非本批引入）**:gateway 集成测试共享 Redis 未清理的既有隔离缺陷,导致一次间歇性
403 flake(见 §6.2)。本批零 gateway 改动。

## 2. 阶段 A:真实授权变更链

### 2.1 既有证据（保留依据）

- 真实 API 撤权/禁用 → 安全版本提升 → 旧 JWT 被拒:`IamMutationApiIntegrationTest`。
- 事务内不维护缓存、提交后维护、版本不匹配回滚:`AuthorizationInvalidationServiceIntegrationTest`。

### 2.2 本批测试（全部走真实 HTTP 入口）

| 测试(文件) | 用例 | 验证点 |
| --- | --- | --- |
| `RealRevocationInvalidationChainIntegrationTest`(4 项) | `roleRevocationThroughRealApiRejectsEveryStaleIdentityWithoutFlushingCaches` | 预热上下文进 Redis → 真实 `DELETE /api/v1/role-assignments/{id}` → 版本 7→8;旧访问令牌、旧刷新会话被拒且会话索引清零;未变更 actor 缓存仍工作;同键重放被权限门拒绝;新登录正常。 |
| | `userDisableThroughRealApiInvalidatesWarmIdentityState` | 真实 `PATCH /api/v1/users/{id}/status` 禁用 → 旧令牌/旧刷新会话被拒;afterCommit 写入新版本键但 `findActiveSecurityVersion` 的 status 谓词使任何版本不可用;USER_DISABLED 审计 1 条。 |
| | `revocationRequestWithStaleActorIdentityIsRejectedBeforeTheTransactionRuns` | **重命名并正名**:actor 安全版本先被带外改动,呈现的令牌在**请求过滤器**即被拒(401 AUTH_SESSION_EXPIRED),命令根本未进入事务;权限/版本/审计未被触碰。这是身份拒绝用例,不是事务回滚。 |
| | `revocationTransactionFailureRollsBackPermissionVersionAuditAndAfterCommitMaintenance` | **新增**:有效身份进入真实撤权入口;业务写入(删分配)与安全版本更新发生、afterCommit 缓存维护已登记;随后命令中止 → 权限/版本/审计**整体回滚**,afterCommit 维护**未执行**,被撤权者预热缓存/旧有效身份/刷新会话仍正常。 |
| `RevocationCacheOutageIntegrationTest`(3 项) | `failedSecurityVersionPutStillRejectsOldTokenThroughDurableVersion` | 注入落在真实 ValueOperations(仅一个 security 键 SET 失败):put 的 catch 吞错 → 版本已提交、旧键值残留,旧令牌仍被 MySQL 持久比较拒绝;下一次成功读回填新版本。 |
| | `failedContextEvictionCannotResurrectOldGrants` | 上下文 evict 的 `redis.delete` 失败:旧版本上下文物理残留但不可达(过滤器先拒旧令牌);新版本上下文从 MySQL 重新加载且不再含被撤权限。 |
| | `failedRefreshRevokeAllStillBlocksOldSessionThroughDurableVersion` | `revokeAll` 失败:旧刷新会话物理存活,但 refresh 轮换重查持久版本后撤销会话并 401;会话索引清零。 |
| `ExpenseIdempotencyRollbackRecoveryApiIntegrationTest`(2 项) | `sameKeySecondRequestCompletesAfterFirstRequestRollsBackInTransaction` | 真实 HTTP 提交(同键 K):第一请求在写入后因审计失败整体回滚;第二同键请求**可核查地被阻塞在幂等唯一键上**(见下),回滚后重新预占并 200。持久结果:1 条幂等行(response_status=200)、1 条 SUBMIT action、1 条成功审计、状态 SUBMITTED v2。 |
| | `sameKeySecondRequestReservesFreshAfterFirstRequestDomainConflict` | 同收敛在第一请求以真实 409 STATE_CONFLICT 响应回滚时成立(first=409、second=200、恰好一次成功)。 |

**撤权中止的注入方式说明**:`AuthorizationInvalidationService.bumpInTransaction` 位于
`@Transactional` AOP 代理之上,`@MockitoSpyBean` 无法对其生效(实测 stubbing 直接触发了真实
代理 → IllegalTransactionState)。改用**测试范围内的 `@Primary` 子类**覆盖:子类先调用
`super.bumpInTransaction`(完成版本更新与 afterCommit 登记),随后按需抛出
`DomainException`(409),既有事务整体回滚、afterCommit 不触发。其余用例走未改动的真实实现。

**同键并发的可核查同步证据**:不再使用“两个 future 都未完成”或“进入未返回”这类
弱同步证据。2026-10-15 收尾批将阻塞证据升级为**数据库层观察**:第一请求停在事务内
(审计 latch)时,测试通过 MySQL 容器的 **root 连接**有界轮询
`information_schema.innodb_trx`,直到第二请求的事务呈现
`trx_state='LOCK WAIT'` 且当前语句为 `INSERT INTO api_idempotency`——即第二请求已
实际执行 reserve 的 INSERT 并在等第一事务的唯一键锁(root 连接是必需的:应用用户无
PROCESS 权限,已验证)。观测到锁等待后才释放第一事务;第二 reserve 的完成只能由回滚
引起。所有等待有界,latch 与线程池在失败路径也释放。第一请求未处理异常不伪造 HTTP 500:
断言 MockMvc 抛出的**实际异常原因**(注入的 IllegalStateException / DomainException 409),
并如实记录。

### 2.3 契约核对（不强行要求 403）

撤权后旧访问令牌/旧刷新会话返回 **401 AUTH_SESSION_EXPIRED**,同键重放被权限门拒,跨组织
一律 404——与既有冻结契约一致,未发现偏差。

## 3. 阶段 B:后台任务恢复与调度

（任务类型恢复矩阵与上一批一致,其中 Intelligence 行更新为:调度分页扫描 + 身份捕获前失败
WARN + 异常文本脱敏。）

### 3.1 调度遍历与分页覆盖（本批实际修复）

生产修复:`CostFactsMapper.recentlyActiveOrgs` 改为确定性 keyset 页:活动窗口由调用方固定,
每页返回严格大于游标 `afterOrgId` 的、按 `org_id` 升序的下一批,UNION 仍去重三来源。
`CostIntelligenceAnalysisService.runDueAnalyses` 每轮固定 `yesterday` 与 `since`,以
`afterOrgId` 逐页推进,并**以“页不满即终止”明确收敛**;单组织/币种失败不阻断本页其余与后续
所有页;本轮日期与活动窗口一致,故重复调度按既有输入身份收敛。

仅加 `ORDER BY` 或提高 `LIMIT` 都不构成修复——本批以分页游标消除遗漏。

`CostIntelligenceSchedulerTraversalIntegrationTest`(3 项):
- `keysetFeedWalksEveryActiveOrganizationOnceAcrossThePageBoundary`:103 个近期活跃组织 +
  1 个窗口外对照。首页恰好满 100(证明必须跨页),跨页全集包含全部 103 个且**无重复**,
  窗口外组织被排除。
- `activityUnionDeduplicatesRealMultiSourceOrganizationAndExcludesStaleOne`:同一组织通过
  **三个真实来源**重复活跃(coverage 戳 + `SETTLED` `gateway_settlement` + `POSTED`
  ledger lineage),在分页全集里恰好出现一次;三来源全部早于窗口的组织被排除。
- `scheduledSweepCoversEveryActiveOrganizationAndConvergesOnRepeat`:真实调用
  `runDueAnalyses`,>100 个活跃组织全部获得 COMPLETED run,窗口外组织为 0;再跑一轮仍每组织
  1 条(身份收敛)。

(早期草稿中“无 `ORDER BY` 已证明每轮遗漏集合变化”的表述不成立,已删除:当时的运行只
证明了分页与去重契约,未做跨轮遗漏集合的对照测量。)

### 3.2 失败可发现性与脱敏（3 项）

`CostIntelligenceSchedulerFailureVisibilityIntegrationTest`:
- `identityCaptureFailureLeavesWarnTraceAndSweepContinues`:身份捕获前失败落 WARN(命名组织/币种/日期),
  无 run 行残留,后续组织照常完成。
- `identityCaptureFailureWarnRedactsSecretShapedFailureText`:**合成敏感标记**(异常消息含
  `api_key=sk-synthetic-marker-...`)验证 WARN 不含该标记、含 `[REDACTED]`(未使用真实凭证)。
- `settledCurrenciesLookupFailureIsConfinedToItsOrganization`(**收尾批新增**):组织级查询失败的
  隔离验证,见 §3.4。

脱敏结论:平台无全局日志脱敏;新增 WARN 使用既有 fail-closed `SecretShapes.redact(...)`
处理异常文本,与 `ImportAttemptExecutor` 的“只记类别、不泄原始消息”取向一致。

### 3.3 组织级查询失败隔离（收尾批修复）

收尾核查发现:`runDueAnalyses` 的 per-run `catch` 只包住 `runAnalysis(...)`,而
`facts.settledCurrencies(orgId)`(及分页查询 `recentlyActiveOrgs`)在 try **之外**——
任一组织的货币查询抛出运行时异常会**终止整轮扫描**,后续组织与后续页全部静默丢失。

修复(生产代码最小改动,`CostIntelligenceAnalysisService.runDueAnalyses`):
- 每组织的 `settledCurrencies` 查询纳入 per-org try/catch:失败写 WARN(经
  `SecretShapes.redact` 脱敏)并 `continue` 到下一组织;
- 页查询失败同样捕获并 WARN,游标以有界探测推进到该页之后,不能死循环;
- 保留既有语义:分页、输入指纹、FAILED 终态、财务语义均不改动。

新测试断言:第一页第一组织的货币查询失败后,该组织无 run 行(WARN 可发现),**同页第二
组织照常 COMPLETED**;页边界行为由既有 Traversal 套件覆盖(未受影响)。

> **2026-10-15 更正**:本节"游标以有界探测推进到该页之后,不能死循环"的结论过强,本批
> 测试结果保留不改。后续复核证实探测推进路径仍存在两个缺陷(P1 短页探测返回 `-1` 被外层
> 当作游标回退重扫首页并清零失败计数,可循环;P2 满页探测直接推进游标但探测读到的 100 个
> 组织从未被处理),且此前的 6 项调度测试均未注入分页查询失败,不足以证明分页异常恢复。
> 复核、修复与回归证据见 [2026-10-15 Cost Intelligence 分页恢复修复](2026-10-15-cost-intelligence-paging-recovery-fix.md)。

### 3.4 PeriodClose 到期时间根因（expected 1 / actual 0）

观察到的失败断言为 `settlementWorker.runOnce()` 期望 1、实际 0。
核对结果:
- **应用与数据库时钟基准不一致**:`GatewaySettlementWorker.runOnce()` 以
  `Instant.now(clock)`(**应用 Clock**)过滤 `next_attempt_at <= now`;而测试此前用
  **数据库时钟** `UTC_TIMESTAMP(6)` 写到期时间。当 MySQL 容器时钟与 JVM 时钟存在偏移时,
  该行相对应用时钟仍“未到期”,组织扫描不含该组织 → `runOnce()==0`。
- 修复:夹具改用**应用时钟**的过去基准 `Instant.now(clock).minusSeconds(1)`,使写入与比较
  同基准。**未放宽任何断言或超时**。
- 后台调度:该套件以 `worker-enabled=true`、`poll-interval=1h` 运行,启动首轮先于夹具,
  不干扰用例;共享测试状态为跨类残留(见 §6.2 的 gateway 隔离缺陷)。
- 单独重跑 7/7 通过**不能单独证明**环境根因;以上为机制层面的因果分析;修复后定向重跑仍 7/7。

### 4. 生产代码改动（最小）

1. `CostFactsMapper.recentlyActiveOrgs(since, afterOrgId, limit)`:确定性 `ORDER BY org_id` +
   keyset 游标(`WHERE active.org_id > #{afterOrgId}`)+ `LIMIT`。仅加排序/提高上限不算修复。
2. `CostIntelligenceAnalysisService.runDueAnalyses`:按页推进、页不满即终止;`catch` 后按
   组织/币种/日期写 WARN,且异常文本经 `SecretShapes.redact(...)` 脱敏。
3. **(收尾批)** 同方法:per-org `settledCurrencies` 查询与页查询纳入各自的有界 try/catch,
   组织级查询失败不再终止整轮扫描;页查询恢复为有界重试(连续失败超过 3 次放弃本轮),
   防止数据源持续不可用时调度线程陷入紧密重试循环;失败 WARN 一律经
   `SecretShapes.redact(...)` 脱敏。
不引入新的 Advisor 自动重试政策、中间件或微服务;保留 FAILED 终态、输入指纹与财务事实语义。

## 5. 测试与支撑改动汇总

| 文件 | 性质 | 数量 |
| --- | --- | --- |
| `CostIntelligenceSchedulerTraversalIntegrationTest`(新) | 测试 | 3 |
| `CostIntelligenceSchedulerFailureVisibilityIntegrationTest`(新) | 测试 | 3(含收尾批组织级隔离 1 项) |
| `RealRevocationInvalidationChainIntegrationTest`(新) | 测试 | 4 |
| `RevocationCacheOutageIntegrationTest`(新) | 测试 | 3 |
| `ExpenseIdempotencyRollbackRecoveryApiIntegrationTest`(新) | 测试 | 2 |
| `PeriodCloseCoordinatorIntegrationTest`(改) | 夹具修正 | 到期时间改用应用时钟 |
| `MinioContainerSupport` / `MinioAuthenticationContainersSupport`(改) | 测试支撑 | tmpfs |

隔离注意:上述新测试的 teardown 显式清理 `audit_event`/`api_idempotency`/coverage/settlement
/ledger 行——共享容器的旧 IAM 套件 setup 不清理这些表,残留行会破坏其清理顺序(定向回归中
发现并已修复)。

### 5.1 MinIO tmpfs 结论

- 镜像(`cgr.dev/chainguard/minio`,固定摘要)`docker image inspect` 显示 **无 `/data` VOLUME**,
  以 **UID 65532** 运行。
- `/data` 以 tmpfs 挂载,默认挂载模式 **1777**,非 root 进程可写,摆脱对镜像层/匿名卷属主的依赖。
- `size=512m` 是**挂载容量上限,不是预分配内存**;tmpfs 页按需占用。不得把 512m 当作已分配内存。
- 测试隔离:各测试支撑类各自的静态容器,容器数据 throwaway。

## 6. 验证记录

报告目录一律绑定隔离构建:`Maven 隔离输出与测试报告:<builds/verify-*>`。

### 6.1 定向回归（本批新增/改动,verify-final-3 之前,全部通过）

命令(backend):`.\mvnw.cmd -B failsafe:integration-test "-Dit.test=<类>" -Dfailsafe.failIfNoSpecifiedTests=false`

| 测试类 | 结果 |
| --- | --- |
| `CostIntelligenceSchedulerTraversalIntegrationTest` | 3/3 通过 |
| `CostIntelligenceSchedulerFailureVisibilityIntegrationTest` | 2/2 通过 |
| `RealRevocationInvalidationChainIntegrationTest` | 4/4 通过 |
| `RevocationCacheOutageIntegrationTest` | 3/3 通过 |
| `ExpenseIdempotencyRollbackRecoveryApiIntegrationTest` | 2/2 通过 |
| `PeriodCloseCoordinatorIntegrationTest` | 7/7 通过 |

2026-10-15 00:xx 定向运行,真实 MySQL 8.4 / Redis / MinIO Testcontainers,串行。

### 6.2 完整 `npm run verify` — 尝试 1（2026-10-14 23:50 – 2026-10-15 00:45）

| 阶段 | 结果 |
| --- | --- |
| 脚本单元 / 生命周期 | 通过 |
| 后端 surefire | 640 项,0 failures、0 errors、1 Windows 平台跳过 |
| 后端 failsafe | 1232 项,0 failures、0 errors、0 skipped —— **BUILD SUCCESS** |
| Gateway surefire | 174 项,0 failures、0 errors |
| Gateway failsafe | 84 项,**1 failure** → `GatewayHeaderLimitIntegrationTest.normalHeadersReachBusinessPath` expected 200 but was 403 —— **BUILD FAILURE** |
| 前端 test / lint / build | 未执行(脚本在 gateway 失败处中止) |

报告目录:`.local-dev/builds/verify-1791993176804-fc99e2d9-5711-463f-a731-9f9b8add445b`。

**失败分类(按证据强度区分)**:
- **已证事实**:gateway 集成测试共享**同一 Redis 容器**(`GatewayMySqlContainerSupport.REDIS`),
  而 `GatewayTestFixture.clean(jdbc)` 只清数据库、不清 Redis(代码可核查)。该 403 在完整运行中
  出现、隔离重跑与配对重跑均未复现(`GatewayHeaderLimitIntegrationTest` 3/3;候选配对 17/17)。
- **机制解释(代码层成立,但未定位到本次 403 的直接成因)**:跨测试类残留的路由/健康/限流
  状态**可能**造成间歇性 `GATEWAY_FORBIDDEN`(403)。
- **结论**:`GatewayHeaderLimitIntegrationTest.normalHeadersReachBusinessPath` 的 403
  保持为**未定位的间歇失败**;共享 Redis 污染是**待验证假设**,在复现并定位前,
  **不**将 `flushAll` 直接记为修复。属既有测试隔离问题,非本批引入,本批零 gateway 改动。

### 6.3 完整 `npm run verify` — 尝试 2（2026-10-15 00:55 起,被硬中断,无结论）

启动后仍处后端集成阶段时,**主机死机**(用户报告电脑死机),运行被硬中断,**无最终结果**。
日志:`.local-dev/reviews/2026-10-14-final-maintenance/verify-final-2.log`。
**已证事实**:死机发生在完整 verify(大量 Testcontainers + JVM)运行期间,该运行被硬中断。
**机制解释**:本机 WSL `memory=2GB`(Docker 实际可用 ~1.86 GiB)下完整 verify 的资源压力
显著,与死机高度相关。**死机的直接原因未经诊断定位,该归因是待验证假设,不是已证事实**。
事后清理了被杀运行遗留的 Testcontainers 容器与临时 `.task-build-*.pom.xml`。

### 6.4 完整 `npm run verify` — 尝试 3（2026-10-15 03:53 起,最终,全部通过）

日志:`.local-dev/reviews/2026-10-14-final-maintenance/verify-final-3.log`;
报告目录:`.local-dev/builds/verify-1792008709624-0b81ef4c-718d-4a55-8d8e-b271d98aea47`。

| 阶段 | 结果 |
| --- | --- |
| 脚本单元 / 生命周期 | 24 + 2 项通过 |
| 后端 surefire | 640 项,0 failures、0 errors、1 Windows 平台跳过 |
| 后端 failsafe | 1232 项,0 failures、0 errors、0 skipped —— BUILD SUCCESS |
| Gateway surefire | 174 项,0 failures、0 errors |
| Gateway failsafe | 84 项,0 failures、0 errors —— BUILD SUCCESS |
| 前端 test / lint / build | 全部通过(含 production build) |

本轮 gateway 84/84 通过(§6.2 的 403 未再现,继续保持未定位间歇失败的分类,不据此宣布修复)。

**证据边界**:`verify-final-3` 对应的是**本收尾批三项改动之前**的代码(基线
`f693700` + 当时的工作区改动)。收尾批改动(§2.2 同键锁等待证据、§3.3 组织级查询隔离、
§6.1 表格更新)之后,按影响范围另取了新的定向验证记录,见 §6.5。

### 6.5 收尾批改动后的定向验证（2026-10-15,收尾批,全部通过）

收尾批三项改动(§2.2 锁等待证据、§3.3 组织级查询隔离、§3.4 文档)完成并重新编译后,
按影响范围重跑(真实 MySQL 8.4 / Redis / MinIO Testcontainers):

命令(backend):`.\mvnw.cmd -B failsafe:integration-test "-Dit.test=<类>" -Dfailsafe.failIfNoSpecifiedTests=false`

| 测试类 | 结果 | 日志 |
| --- | --- | --- |
| `CostIntelligenceSchedulerFailureVisibilityIntegrationTest` | 3/3 通过 | `closeout-targeted-1.log` |
| `ExpenseIdempotencyRollbackRecoveryApiIntegrationTest`(锁等待证据版) | 2/2 通过 | `closeout-targeted-2.log` |
| `CostIntelligenceSchedulerTraversalIntegrationTest` | 3/3 通过 | `closeout-targeted-2.log` |

后端生产代码编译通过;后端全部 640 项单测在收尾批代码上重跑通过(0 失败,日志
`closeout-unit-and-targeted.log`,该次运行的集成阶段因下述缺陷修复前的问题被主动中止,
单测结果有效)。Gateway 与前端未被本批改动触碰,不重复 §6.4 的全绿验证。

**收尾批自查发现并修复的缺陷**:`runDueAnalyses` 的页查询恢复路径初版在数据源持续不可用
时会形成紧密重试循环(探测失败返回原游标,外层立即重查),在单测上下文中刷出数万条 WARN。
修复:页查询恢复改为**有界重试**(连续失败超过 3 次放弃本轮并 WARN,下一轮调度自然重试);
修复后定向重跑无该现象,全部通过。

> **2026-10-15 更正**:本节"有界重试"结论过强,本批运行结果保留不改。"连续失败超过 3 次"
> 的计数在"首页成功"时被清零,配合探测路径的短页 `-1` 回退,退出限制并不能阻止"末页失败→
> 回扫首页→再次失败"的循环;探测路径在满页时还会跳过未处理组织。修复与证据见
> [2026-10-15 Cost Intelligence 分页恢复修复](2026-10-15-cost-intelligence-paging-recovery-fix.md)。

（提交前补记:最终提交号见仓库 log;本节记录对应收尾批全部代码改动。）

### 6.6 定向回归（本批新增/改动,截至 verify-final-3 之前,全部通过）

命令(backend):`.\mvnw.cmd -B failsafe:integration-test "-Dit.test=<类>" -Dfailsafe.failIfNoSpecifiedTests=false`

| 测试类 | 结果 |
| --- | --- |
| `CostIntelligenceSchedulerTraversalIntegrationTest` | 3/3 通过 |
| `CostIntelligenceSchedulerFailureVisibilityIntegrationTest` | 2/2 通过(收尾批增至 3 项,见 §6.5) |
| `RealRevocationInvalidationChainIntegrationTest` | 4/4 通过 |
| `RevocationCacheOutageIntegrationTest` | 3/3 通过 |
| `ExpenseIdempotencyRollbackRecoveryApiIntegrationTest` | 2/2 通过(收尾批升级锁等待证据,见 §6.5) |
| `PeriodCloseCoordinatorIntegrationTest` | 7/7 通过 |

2026-10-15 00:xx 定向运行,真实 MySQL 8.4 / Redis / MinIO Testcontainers,串行。
对应**收尾批改动前**的代码;§2.2 与 §3.3 所述测试改动后的新验证记录见 §6.5。

## 7. 限制与遗留

- **已修复**:调度固定 `LIMIT 100` 遗漏(keyset 分页);新增 WARN 的可发现性与脱敏;
  **组织级 `settledCurrencies` 查询失败不再终止整轮扫描(收尾批)**;
  PeriodClose 到期时间时钟基准。
- **未解决(非本批引入)**:gateway 集成测试共享 Redis 未清理的既有隔离缺陷;
  `GatewayHeaderLimitIntegrationTest` 一次 403 保持为未定位的间歇失败。共享 Redis 污染
  是待验证假设,复现定位前不把 flushAll 记为修复;建议后续先在小范围验证再全量。
- **待验证风险**:Advisor 孤儿回收无重试上限(引入上限属新自动重试政策)。
- **已接受**:预占到期 ≠ 无费用(PENDING_HOLD 不释放);调度身份捕获前失败可由 WARN 发现。
- **DEFERRED**:真实 Provider/OpenCode 联调、独立浏览器组织隔离。
- 远程 CI:未执行。

## 8. 建议下一批

1. gateway 测试隔离:先复现并定位共享 Redis 残留与 403 的因果关系,再决定是否在
   gateway 测试支撑清理中加入 Redis flush(先小范围验证再全量)。
2. Advisor job 重试上限/人工重放出口的产品决策(涉及自动重试政策,需澄清)。
3. 完整 HTTP 层多请求竞争(>2)与多实例传播(双后端实例)验证。
