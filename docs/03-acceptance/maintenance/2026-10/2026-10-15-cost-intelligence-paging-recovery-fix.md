# 2026-10-15 Cost Intelligence 调度分页恢复缺陷修复（P1/P2）

本记录针对 [2026-10-15 维护交接](2026-10-15-maintenance-handoff.md) 指出的两个尚未修复的
分页恢复缺陷,基于真实源码独立复核后修复并验证。修复前基线:
`codex/history-coverage-20261012@c6ef16731bf1d2e2daea0d6316913cc76f9800b3`(推送前已核对
本地 HEAD 与 `origin/codex/history-coverage-20261012` 一致,工作区无未提交内容)。
本批修复提交:`7e47ec05bae72701474d10c6f8f4debac243ec0d`(代码与测试,本记录所在提交为其
后续文档提交)。本记录随分支推送供远端独立代码审查,不代表发布验收通过。

## 1. 修复前复核（c6ef167 真实代码）

两缺陷均在本基线源码上独立复核成立,并以单元测试在修复前复现(见 §4.1):

- **P1(短页恢复造成重复扫描与循环)**:`advancePastCurrentPage` 探测读到不足 100 个组织时
  返回 `-1`;外层未将 `-1` 视为终止信号,而是以其为游标重新查询(`org_id > -1` 命中首页),
  且首页成功后 `pageRecoveryAttempts` 清零,"连续失败超过 3 次放弃本轮"的退出限制在该路径
  上永远无法触发。末页主查询持续失败而探测持续成功时,游标在 `100 → -1 → 100 → -1 …`
  循环,前 100 个组织被重复访问,第 101 个组织始终无法通过主查询处理。即使只发生一次,
  首页也会被重复处理一遍。
- **P2(满页恢复跳过组织)**:探测读到满 100 个组织时,`advancePastCurrentPage` 直接返回
  `page.getLast()` 推进游标,但探测读取的这 100 个组织从未被处理。首页瞬态失败一次,
  前 100 个组织即被静默跳过,只剩第 101 个被处理。

影响范围:仅 `CostIntelligenceAnalysisService.runDueAnalyses` 的调度遍历控制流。
分析内容、输入指纹、FAILED 终态、财务语义均不受影响;缺陷影响的是"哪些组织在本轮被
分析"(遗漏/重复),不产生错误金额。

## 2. 修复内容（最小改动）

文件:`backend/src/main/java/com/aicostops/intelligence/application/CostIntelligenceAnalysisService.java`,
仅改动 `runDueAnalyses` 的页查询失败处理与相关常量/注释,并整体删除
`advancePastCurrentPage`:

1. 查询失败时**游标保持不变**,原地重试同一页;
2. 同一游标最多 **1 次初始查询 + 3 次重试 = 共 4 次**(`MAX_PAGE_FETCH_ATTEMPTS = 4`,
   明确"总尝试次数"与"重试次数"的区别,失败 WARN 注明 `attempt N of 4`);
3. 重试成功后按正常路径处理该页全部组织(每组织/币种分析失败隔离、每组织
   `settledCurrencies` 查询失败隔离均保留不动);
4. 只有**成功处理完当前页**才推进游标(`afterOrgId = page.getLast()` 在处理循环之后);
5. 4 次全部失败则本轮放弃,写 WARN(`abandoned after 4 consecutive page fetch failures
   at organization N`),下一轮调度自然重试,不循环、不跳页;
6. 删除不安全的探测路径 `advancePastCurrentPage`(跳页恢复);
7. 正常语义不变:满页继续、短页(含空页)结束本轮。

未新增 Redis 锁、消息队列、微服务或通用重试框架;未改动 `CostFactsMapper`、预测算法、
权限、幂等、事务契约;无数据库迁移、无 API 变化、无财务语义变化。

### 为什么采用该方案

- 游标不动 + 原地重试是唯一同时满足"不遗漏、不重复、不循环"的最小控制流:任何"失败后
  推进"都要么跳过未处理组织(P2),要么依赖探测读取结果(而探测结果本身可能是页,处理与否
  都引入新的正确性问题)。
- 有界放弃依赖 MySQL 为最终真相的既有前提:分析是幂等可重放的派生证据,下一轮调度按
  既有输入身份收敛,放弃本轮不丢失任何财务事实。

## 3. 测试改动

### 3.1 新增纯单元测试(先于修复编写,Mockito,无数据库)

`backend/src/test/java/com/aicostops/intelligence/application/CostIntelligenceSchedulerPagingRecoveryTest.java`
(5 项)。被测对象是真实 `runDueAnalyses` 控制流,仅 mock `CostFactsMapper` 边界
(脚本化游标序列/失败注入)与 `CostIntelligenceRunService.run`(记录被分析的组织),断言
游标序列、页查询次数、处理组织集合与 WARN 内容:

| 用例 | 冻结的契约 |
| --- | --- |
| `fullPageTransientFailureProcessesEveryOrganizationExactlyOnce` | P2:首页失败 1 次,101 个组织全部且仅处理一次;游标 `0,0,100`;共 3 次页查询 |
| `lastShortPageTransientFailureResumesInPlaceWithoutRescanning` | P1:末页失败 1 次,不回扫首页、不遗漏第 101 个组织;游标 `0,100,100` |
| `persistentDatasourceFailureAbandonsRoundAfterBoundedAttempts` | 持续失败恰好 4 次查询后本轮终止,0 个组织被处理,1 条 `abandoned` WARN |
| `settledCurrenciesFailureIsConfinedToItsOrganizationAcrossPages` | 跨页时第 5 个组织货币查询失败仅影响该组织,其余 100 个组织含后续页照常处理 |
| `repeatedRoundsTraverseTheFullDeterministicFeed` | 重复调度两轮,每轮完整遍历同一确定性序列 |

### 3.2 新增真实 MySQL 集成回归测试

`backend/src/test/java/com/aicostops/intelligence/CostIntelligenceSchedulerPageFailureIntegrationTest.java`
(3 项,Testcontainers MySQL 8.4,101 个真实活跃组织)。故障注入在真实 MyBatis mapper 的
委托代理(`AdditionalAnswers.delegatesTo`)上,生产控制流走真实 `recentlyActiveOrgs` 查询;
因测试上下文 `@EnableScheduling` 会在启动时立即触发一次后台扫描干扰调用计数,该测试类
将 `CostIntelligenceScheduler` 外壳 Bean mock 掉,被测服务不受影响:

| 用例 | 断言 |
| --- | --- |
| `fullPageTransientFailureStillProcessesEveryOrganizationOnce` | 首页失败 1 次后恢复,101 个组织各恰有 1 条 COMPLETED run;恰 3 次页查询 |
| `lastShortPageTransientFailureResumesInPlaceWithoutRewind` | 末页失败 1 次,101 个组织全部 COMPLETED;游标 `0 → c → c`(原地重试,无回退) |
| `persistentDatasourceFailureAbandonsRoundAfterBoundedAttempts` | 恰 4 次页查询后终止,0 条 run |

### 3.3 既有测试

- `CostIntelligenceSchedulerTraversalIntegrationTest`(3 项,真实 MySQL):keyset 跨页无
  遗漏无重复、三来源去重、**重复调度持久运行记录收敛**——任务要求第 5 项场景由此套件
  在真实 MySQL 上证明,本批未改动它。
- `CostIntelligenceSchedulerFailureVisibilityIntegrationTest`(3 项):身份捕获前失败 WARN、
  脱敏、组织级查询隔离——语义未改动,全部继续通过。
- 此前 6 项调度测试均未注入分页查询失败,因此它们此前**不能**证明分页异常恢复——这正是
  本批新增 §3.1/§3.2 的原因。

## 4. 验证记录

环境:Windows,JDK 21,Docker(Testcontainers MySQL 8.4 / Redis / MinIO)。以下为实际命令、
结果与退出码(未放宽断言、未扩大超时;失败运行如实记录)。

### 4.1 修复前(缺陷复现,基线 c6ef167)

```text
命令: .\mvnw.cmd -B -q -Dtest=CostIntelligenceSchedulerPagingRecoveryTest test
结果: Tests run: 5, Failures: 4, Errors: 0
退出码: 1
```

4 项失败直接呈现 P1/P2 行为(短页失败后出现 `cursor -1` 重扫、满页失败后处理集合缺失、
持续失败超出 4 次上限、重复轮次为空),即缺陷在修复前真实复现。

### 4.2 修复后定向单元测试(Mockito,无数据库)

```text
命令: .\mvnw.cmd -B -q -Dtest=CostIntelligenceSchedulerPagingRecoveryTest test
结果: 5/5 通过(0 failures, 0 errors)
退出码: 0
```

### 4.3 新增集成回归(真实 MySQL Testcontainers)

```text
命令: .\mvnw.cmd -B -q "-Dgroups=integration" "-Dit.test=CostIntelligenceSchedulerPageFailureIntegrationTest" "-DfailIfNoSpecifiedTests=false" verify
结果: 3/3 通过
退出码: 0
```

如实记录:该测试第一版用 `@MockitoSpyBean` + `doCallRealMethod` 注入故障,因 MyBatis
mapper 接口方法无"真实方法"可调用而报 `MockitoException`(退出码 1),改为委托代理后
全部通过。该失败是测试基建问题,非生产代码问题。

### 4.4 既有相关集成测试(真实 MySQL Testcontainers)

```text
命令: .\mvnw.cmd -B -q "-Dgroups=integration" "-Dit.test=CostIntelligenceSchedulerTraversalIntegrationTest,CostIntelligenceSchedulerFailureVisibilityIntegrationTest,CostIntelligenceRunIntegrationTest,CostIntelligenceAnalysisPipelineIntegrationTest,CostHistoryCoverageIntegrationTest" "-DfailIfNoSpecifiedTests=false" verify
结果: 全部通过(Traversal 3 + FailureVisibility 3 + Run + AnalysisPipeline + Coverage)
退出码: 0
```

其中"重复调度遍历正确且持久运行记录收敛"由
`CostIntelligenceSchedulerTraversalIntegrationTest.scheduledSweepCoversEveryActiveOrganizationAndConvergesOnRepeat`
在真实 MySQL 上证明。

### 4.5 后端全量单元+架构测试(Mockito 为主,无外部容器)

```text
命令: .\mvnw.cmd -B "-DexcludedGroups=architecture,integration" test
结果: Tests run: 609, Failures: 0, Errors: 0, Skipped: 1(Windows 平台跳过), BUILD SUCCESS
退出码: 0
```

### 4.6 后端完整 verify

```text
命令: .\mvnw.cmd -B verify
结果: BUILD SUCCESS,退出码 0(全量 surefire + 全量 failsafe 集成,真实 MySQL/Redis/MinIO Testcontainers)
退出码: 0
```

| 阶段 | 结果(本运行实测汇总) |
| --- | --- |
| 后端 surefire(单元+架构,含本批新单测) | 645 项,0 failures,0 errors,1 Windows 平台跳过 |
| 后端 failsafe(全量集成,含本批 3 项新回归与全部既有 Intelligence/财务套件) | 1236 项,0 failures,0 errors,0 skipped |

### 4.7 工作区回归(根目录 `npm run verify`)

未在本批执行完整根目录 `npm run verify`(后端+Gateway+前端全链)。理由:本批改动仅触及
backend 一个生产文件及其测试;Gateway 与前端零改动,其最近一次全绿完整验证记录见
[2026-10-14 审查 §6.4](2026-10-14-authorization-change-and-recovery-review.md)。该全绿
验证早于本批,本批对 backend 的完整回归以 §4.5/§4.6 为准,不得将 §6.4 的结果套用到
当前分支代码。

## 5. 证据分类

- **真实 MySQL(Testcontainers)上运行的验证**:§4.3(新增分页故障回归 3 项)、§4.4(既有
  调度/运行/管线/覆盖集成)、§4.6 failsafe 全量集成。
- **仅 Mockito 单元测试(无数据库)**:§4.1/§4.2(分页恢复 5 项)、§4.5(surefire 全量)。
  单元测试冻结的是调度控制流分支逻辑,不声称验证真实数据库故障行为或查询频率。

## 6. 数据库迁移 / API / 财务语义变化

均无。未新增 Flyway 迁移;未改动任何 API 契约;组织与币种隔离、输入指纹与覆盖修订、
FAILED 终态、POSTED 账本不可变、冲正/负金额、真实零与未知历史的区分、预测算法与样本
等级口径全部保持不变。

## 7. 遗留风险与未验证边界

- **真实生产级数据库故障模式未验证**:测试注入的是应用层瞬时异常;未验证真实 MySQL
  连接池耗尽、主从切换、长事务超时等场景下 4 次重试是否足够。设计立场是"重试耗尽即
  放弃本轮,下一轮调度自然重试",故这些场景退化为"本轮部分组织延迟到下轮被分析",
  不产生金额错误,但分析时效会延迟一个调度周期。
- **多实例并发调度**:两个后端实例同时调度时,页游标各自独立,可能重复拉取同一页;
  正确性由 MySQL 输入身份/claim 收敛保证(既有 Traversal 收敛测试),但未做双实例页级
  竞争的定向验证(与本批缺陷无因果关系,维持原状)。
- **完整根目录 `npm run verify` 未在本批重跑**(见 §4.7);Gateway 与前端无改动。
- Gateway 的一次 403 间歇失败、共享 Redis 污染假设、真实 Provider/OpenCode 联调与独立
  浏览器组织隔离仍为 [交接记录](2026-10-15-maintenance-handoff.md) 所列的未解决/DEFERRED
  项,本批未触碰。

## 8. 对旧文档过强结论的更正

不改写任何历史测试结果,仅在
[2026-10-14 授权与恢复审查](2026-10-14-authorization-change-and-recovery-review.md) 的
§3.3 与 §6.5 原地补充"2026-10-15 更正"注记:

- 旧文"游标以有界探测推进到该页之后,不能死循环"、"页查询恢复为有界重试……防止紧密
  重试循环"的结论过强:探测路径本身在短页时回退游标、在满页时跳过未处理组织,且失败
  计数会在首页成功时清零,退出限制对该循环路径无效。
- 旧文 6 项调度测试"通过"属实,但它们均未注入分页查询失败,不构成"已有测试足以证明
  分页异常恢复"的证据;本批 §3.1/§3.2 补齐该证据。
