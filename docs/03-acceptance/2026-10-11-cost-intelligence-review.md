# 2026-10-11 成本分析整理复核

## 结论

源码职责拆分符合设计，未发现本轮引入的明确业务回归。初次审查指出的回滚测试与打包验收两项已完成补测，补测源码、已有可执行 JAR 与报告已再次核对。默认 MinIO 镜像的独立收尾见 [测试镜像验证记录](2026-10-11-minio-test-image.md)。

以下“需要处理的事项”保留为初次审查的历史依据，关闭情况见文末；不代表补测后仍使用原测试夹具。

最终复核：两项原审查事项与默认测试镜像问题均已关闭。无镜像覆盖参数的完整 verify 为 609 项单元（跳过 1）、1191 项集成，失败错误均为零；含打包与 repackage。新产物内容与四类编译输出一致性通过，原 8080 后端保持 UP。具体命令、报告位置及验证限制见上述测试镜像记录。

初次审查检查实际 application 文件、Controller/Scheduler/Advisor diff、响应类型、新增测试、Maven 配置和已有测试 XML。当时没有修改业务代码、重新运行测试或验证新 JAR 启动；后续补测与默认镜像验证情况见文末及独立记录。

## 需要处理的事项

### P2：流水线测试没有制造已写入的异常和建议

位置：`backend/src/test/java/com/aicostops/intelligence/CostIntelligenceAnalysisPipelineIntegrationTest.java:62`。

夹具只创建空组织，`SavingsRecommendationGenerator` 被完全替换为 MockitoBean，generate 在执行任何建议写入之前直接 doThrow。异常检测没有非零账本历史，因此不会生成异常；空组织的全窗口补零序列会生成组织预测。

所以该测试能够检验已生成预测的回滚及 FAILED 记录，但 cost_anomaly、savings_recommendation 的零行断言并不能检验它们的回滚：这两类记录从未被写入。即使未来 Generator 错误地独立提交建议，当前测试也不能识别，因为真实 Generator 被整个替换。

建议至少在故障触发前、同一分析事务内确认三类派生记录实际存在，再抛异常，并在事务结束后确认全部消失。可复用已有账本/定价夹具产生异常和建议，使用 spy 在真实生成写入之后注入失败；也可对建议持久化的明确步骤注入故障。应有事务内的非零前置断言，避免零行断言空过。

同时修正原验收记录第 4 节“已写入的异常/预测/建议全部回滚”的覆盖描述，补测通过后再恢复这项结论。

### 打包与启动仍是未完成的验收

Maven 命令 `test failsafe:integration-test failsafe:verify` 运行了单元测试生命周期及集成测试插件，但没有走完整 verify 生命周期中的 package 和 Spring Boot repackage。当前 POM 中包含 spring-boot-maven-plugin，不能把测试通过视作可执行 JAR 已验证。

原验收记录已经披露 JAR 占用，应进一步把“覆盖范围与 verify 相同”明确改为“单元与集成测试范围相同，打包及产物启动未验证”。本轮未停止本地后端进程。

建议在合适的维护时机解决产物占用，执行完整 verify，确认生成的 Boot JAR 可以启动；MinIO 使用测试支持已有的镜像覆盖参数即可作为临时验证条件。默认镜像可重复拉取的问题应单独收尾，不能依据一次镜像拉取错误就断言上游镜像永久消失。

## 已核对的正确边界

- 原总服务删除；生产和测试源码未发现旧类引用。
- RunService 保留 TransactionTemplate、参与外层事务、回滚 afterCompletion 及独立失败记录实现。
- Generator 没有新增独立事务；分析回调与结果写入沿用原事务。
- 三个 public 建议命令带 @Transactional；状态条件更新、审计及回读在同一入口事务内。
- COST_READ/BUDGET_READ/BUDGET_MANAGE、组织参数及 APPLIED 路由关联检查保留。
- Controller 8 条路径、默认值及响应字段类型保留；金额字符串和数字 ID 未改。
- 预算风险仍区分预占与未来预测，缺失作用域预测为零；Advisor diff 仅 4 行依赖/调用适配。
- 查询日期、版本、时间窗口、补零、建议同向量回放、门槛和指纹实现保持原语义。

## 已有测试证据（初次审查）

读取 `backend/target/surefire-reports/TEST-*.xml`：82 个报告，609 项，失败 0、错误 0、跳过 1。

读取 `backend/target/failsafe-reports/TEST-*.xml`：182 个报告，1189 项，失败 0、错误 0、跳过 0；failsafe-summary 同样为 1189 项、无失败错误。

新增三类测试报告共 8 项，均通过。报告生成时间为 2026-10-10 晚间；这些是实施 AI 留下的执行证据，不是本轮重新执行的结果。不能用通过数量消除上面的场景覆盖缺口。

`git diff --check` 无格式问题。前端 38 项通过记录来自实施报告，本轮未重新执行，也未独立获得其终端日志。

## 下一步

先补真实派生写入后的失败回滚测试、同步验收表述，并完成完整打包验证。之后再设计 Advisor 证据构建职责整理；不继续拆当前分析服务，也不同时进入网关和财务对账。

## 收尾结果（实施 AI 反馈，2026-10-11）

上述两项均已处理，详见 [成本分析整理验收记录](2026-10-10-cost-intelligence-refactor.md) 第 3、4、5 节。

- **P2 回滚测试已重写**：改用 `@MockitoSpyBean` + `callRealMethod()`，让真实生成器先在同一分析事务内
  写入异常、预测与建议记录，再注入失败；断言在事务内先行确认三类记录存在，事务结束后确认全部消失。
  另加"同夹具无故障即正常提交"的对照用例。并做了变异验证：移除 `callRealMethod()` 后前置断言立即失败，
  证明零行断言不再空过。夹具改用真实 POSTED 账本日序列与两个已定价候选。
- **打包与启动已验证**：停掉占用产物的本机后端后执行完整 `verify`（609 单元 / 1191 集成，失败 0），
  `repackage` 执行；实测 JAR 内容（含 9 个新拆分类、无旧总服务、Advisor 适配已生效、类 SHA-256 与编译输出一致）、
  `local` profile 启动成功、`/actuator/health` 返回 UP，并从启动期 TRACE 日志确认 cost-intelligence 8 条
  与 ai-advisor 的 4 个 URL、5 个方法映射均已注册（profile 同时支持 GET/PUT）。验证后已按原命令恢复本机后端。

过程中发现并修复了本轮新增测试自身的一个时区缺陷：`AdvisorBudgetRiskEvidenceIntegrationTest` 用本地时区
`LocalDate.now()` 插入分析任务，而生产代码用注入的 `Clock.systemUTC()` 推算"昨日"，在本地已跨天而 UTC 未跨天的
时段两者相差一天。这是测试夹具缺陷，不是业务回归。原验收记录中"已写入的异常/预测/建议全部回滚"的表述已按
补测后的实际情况重写。

当时遗留的默认镜像事项现已关闭：两类 MinIO 测试容器共用固定镜像摘要，无覆盖参数的完整 verify 已通过。
本轮复核的单元 609 项（跳过 1）、集成 1191 项均无失败错误，包含打包与 repackage；本地后端保持 UP。
见 [默认镜像收尾记录](2026-10-11-minio-test-image.md)。下一步可以单独设计 Advisor 证据构建职责整理。
