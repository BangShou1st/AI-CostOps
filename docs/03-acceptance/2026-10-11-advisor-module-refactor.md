# 2026-10-11 Advisor 模块整理与验证

后续联合审查补充了当前执行次数的终结校验与真实数据库回归，并按模块整理了本地提交，见 [三个模块联合审查](2026-10-11-cross-module-review.md)。本文记录首次拆分阶段；下文方法体一致性结论不包含后续增加的次数校验。

## 完成范围

后端 AdvisorService 已删除，没有 facade 或兼容服务入口。第一阶段提取的证据构建与快照序列化继续保留，本轮进一步完成配置、凭据轮换、任务命令、任务查询与 worker 生命周期整理。

| 文件 | 职责 |
| --- | --- |
| AdvisorProfileService（131 行） | profile 查询、组织校验、版本更新与共同事务协调 |
| AdvisorCredentialRotator（65 行） | 版本绑定的内部凭据轮换与模型授权 |
| AdvisorJobCommandService（118 行） | 请求解释、冻结快照、首次尝试与显式重试 |
| AdvisorJobQueryService（73 行） | 组织内任务查询、尝试与解释响应装配 |
| AdvisorJobLifecycleService（156 行） | 领取、网关联接、fencing、终结与恢复的短事务 |
| AdvisorRequests / AdvisorReadModels / AdvisorJobClaim | 独立输入、响应和领取结果 |
| AdvisorErrors | 复用原来的错误码、HTTP 状态、标题和详情 |

AdvisorMapper 同样删除，按 Profile / Job / Evidence 三个职责拆分，数据库投影移到 AdvisorRows。保留的 SQL 与拆分前逐项核对，未改变查询、组织条件、锁、状态条件、排序或金额口径。删除 6 个没有后端调用方的旧方法，包括没有组织条件的旧模型查询；Gateway 自己实际使用的查询不受此清理影响。另核对 21 个迁移后的服务方法体，除依赖调用名调整外保持一致，凭据轮换方法体也与原实现一致。

Controller 保留 4 个 URL、5 个方法映射。前端 advisor/api 的 URL、输入、响应、轮询和重试契约均保持；前端页面职责集中，继续保留。后端和 Gateway 是两个独立进程，未建立跨 Maven 模块的服务调用。

Gateway 的 AdvisorInferenceWorker 提取 AdvisorSnapshotPromptBuilder 和 AdvisorNarrativeValidator。worker 保留完整受治理执行、用量结算、失败分类及已关联执行恢复路径；领取和状态写入仍为短事务，Provider I/O 仍在其外。没有增加第二个 Scheduled worker。

当前阅读路径见 [Advisor 模块阅读与维护](../02-development/advisor-module-walkthrough.md)。

## 事务与补测

配置更新仍在 public @Transactional 入口内完成版本替换、凭据轮换、旧任务与尝试同时失效和审计。请求仍将 job、不可变 snapshot、attempt 和 audit 一起提交；worker 终结仍要求任务和当前尝试更新行数一致，失败则整体回滚。辅助组件不使用 REQUIRES_NEW。

新增 profileAuditFailureRollsBackRotationAndSupersededWork：

- 在真实数据库事务内先观察两个配置版本、新 ACTIVE 凭据、旧 REVOKED 凭据及 FAILED 任务和尝试。
- 在 profile 审计位置注入故障。
- 事务结束后比较原配置、全部凭据字段与模型授权，确认没有残留新版本；原任务与尝试恢复 PENDING，审计行数恢复。
- 测试方法没有外层事务，Mapper 没有 mock；二进制摘要按十六进制规范化后比较，避免数组引用比较掩盖结果。

Gateway 新增 2 项证据协议测试：核对预占、未来预测、金额字符串 scale、冻结风险分类与引用集合；拒绝未知引用和模型金额字段。

## 测试环境修复

首次 Gateway 完整验证发现 GatewayMySqlContainerSupport 只应用 V1～V25，而 worker 已读取 V26、V27 增加的绑定与冻结字段。已补齐迁移，并补上清理 snapshot 以及解除测试凭据到 profile 的外键绑定顺序。生产迁移与数据库配置没有改变。修复后重新完整 verify，不再出现 Advisor 缺列错误。

## 已完成验证

最终后端代码使用独立输出目录，原 pom.xml 没有修改，临时 POM 仅设置 build.directory，并在命令结束后移除：

```powershell
.\mvnw.cmd -B -f .advisor-final-verification.pom.xml '-Dit.test=*Advisor*IntegrationTest,ScopedBudgetRiskIntegrationTest,CostIntelligenceRunIntegrationTest' verify
```

单元/架构/契约 610 项（1 项 Windows 条件跳过），定向集成 28 项，失败 0、错误 0。包括 8 项终态与回滚集成测试。package/repackage 完成，耗时 3 分 8 秒。

Gateway 最终代码：

```powershell
.\mvnw.cmd -B verify
```

单元/架构 173 项、集成 84 项，失败 0、错误 0；package/repackage 完成，耗时 6 分 32 秒。

Frontend：

```powershell
npm run lint
npm run build
npm test -- --run --maxWorkers=2
```

lint、构建通过，70 个文件、531 项测试通过。首次默认并发执行在对账页面出现 1 项等待渲染失败；降低并发后全量通过，没有更改产品代码或放宽断言。本轮没有浏览器/UI 验收。

最终后端 JAR 在 local profile、8099 端口启动，健康状态 UP；验证实例随后通过前台会话终止，8099 已无监听。原 8080 实例 PID 52352 保持运行且健康状态 UP，尚未切换到本轮 JAR。

后端全量独立验证：

```powershell
.\mvnw.cmd -B -f .advisor-full-verification.pom.xml verify
```

610 项单元/架构/契约（1 项 Windows 条件跳过）、1193 项集成，失败 0、错误 0。package/repackage 和 failsafe verify 完成，BUILD SUCCESS，耗时 31 分 17 秒；没有设置 MinIO 镜像覆盖。

全量验证在职责拆分后启动。期间又清理了未使用的 Mapper 方法、复用了等价错误工厂、调整格式并补充配置审计回滚测试，因此最终源码另执行了上面的 610 + 28 项验证及独立 JAR 启动检查。1193 是首次全量集成结果，28 是最终代码的相关集成结果，不能把重复运行相加后称为新的全量测试数。

规模测试包含 1 万条预算的查询计划核对与 1 万条成本事实的报表测试；后者耗时约 7 分钟，主要在真实数据准备阶段。没有跳过这些测试或更改其规模、断言。

## 证据位置与后续

- 最终后端产物及报告：C:/Users/32962/AppData/Local/Temp/aicostops-advisor-final-877f29b6ccae4463ac390e9bd8b1f835/target。
- 后端全量报告：C:/Users/32962/AppData/Local/Temp/aicostops-advisor-full-4052db7c5c6e49dea1539a9d61200ddd/target；日志在其父目录 verify.log。
- 最终后端日志：上述目录的父目录 verify.log。
- Gateway 日志：%TEMP%/aicostops-advisor-gateway-verify-final.log；报告在 gateway/target。
- 前端日志：%TEMP%/aicostops-advisor-frontend-test-final.log。

未提交或推送 Git，保留既有报销、成本分析、MinIO 和前端的未提交改动。Advisor 整理完成后，建议先审查这些改动并按模块形成清晰提交，再选择下一个模块；无需继续拆分 Advisor。
