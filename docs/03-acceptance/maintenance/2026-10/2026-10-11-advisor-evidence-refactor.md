# 2026-10-11 Advisor 证据构建职责整理

此文记录第一阶段的结构和验证。后续已继续整理配置、任务与 Gateway，当前结构请读 [Advisor 模块阅读与维护](../../../02-development/guides/advisor-module-walkthrough.md)；下文的 AdvisorService 为当时入口名称。

## 改进结果

`AdvisorService` 从 671 行减到 493 行。按证据的构建与序列化两条阅读路径提取组件，保留现有 application 目录，没有新增 Maven 模块、HTTP 接口或兼容 facade。

| 文件 | 职责 |
| --- | --- |
| `advisor/application/AdvisorService.java` | 权限与 profile 检查、请求事务、任务/快照/首次尝试持久化、审计及原 worker 生命周期 |
| `advisor/application/AdvisorEvidenceAssembler.java`（163 行） | 本组织四类主题事实读取、异常驱动解析、确定性预算风险调用、受限 envelope 装配 |
| `advisor/application/AdvisorEvidenceSnapshotSerializer.java`（93 行） | 金额字符串、快照 facts/drivers/summary 与模型可引用 ID 的序列化 |
| `advisor/application/AdvisorEvidence.java` | 继续负责有界证据模型及原指纹算法，未改动 |

Assembler 的 build 与 Serializer 的入口为包内方法，由通过授权的 AdvisorService 使用。两类组件不创建事务、不持久化结果、不调用 Provider，也不提供新 HTTP 路由。没有为四类主题再创建四个独立策略类。

## 保留的行为与事务

请求仍在 AdvisorService.requestExplanation 的 public @Transactional 入口执行：

```text
AI_ADVISOR_USE → 当前 profile → 主题类型校验
→ 本组织事实与 envelope → fingerprint 与引用 JSON
→ job insert → immutable snapshot insert → attempt 1 insert → audit append
```

实际写入顺序与事务边界不变；job/profile 绑定、请求人 app_user ID、显式重试及 worker fencing/终结逻辑保留原实现。辅助组件没有 REQUIRES_NEW 或异步执行。

- ANOMALY/FORECAST/SAVINGS/BUDGET_RISK 均沿用原 Mapper 的组织过滤，外组织或缺失主题保持原 404。
- 用户只能提交主题身份；原客户端金额等兼容字段仍被忽略。
- 异常驱动来自已持久化 JSON，保留原边界、解析规则与损坏数据返回空列表的策略。
- 预算风险仍由 BudgetRiskQueryService 计算，保持预占、预测、作用域和风险分类口径。
- 金额与驱动 delta 保持 toPlainString 字符串及 scale；驱动因素引用 ID 保持主题小写、主题 ID 和驱动顺序。
- 证据 schema version、摘要字段、日期、事实顺序、指纹算法及 Gateway 冻结证据协议保持原样。

## 新增保护

1. `AdvisorEvidenceSnapshotSerializerTest`：包含引号、换行、中文和负数驱动的证据，经快照 JSON 字符串重新构造后，指纹与引用 ID 一致；明确断言金额 scale 和 JSON 字符串类型。
2. `AdvisorEvidenceSnapshotIntegrationTest` 新增 FORECAST/SAVINGS 真实数据库场景，核对金额、事实 ID、方法摘要及空驱动。
3. 同一集成测试新增审计故障回滚：故障触发前，在请求事务内确认 job、snapshot、attempt 各 1 行；事务结束后确认三类数据均消失且没有请求审计行。没有测试方法外层事务，也没有 mock Mapper。

既有异常快照、拒绝客户端金额、外组织拒绝、显式重试、profile 版本冻结、预算作用域与 worker 终结测试继续执行。

## 本轮验证

为保留正在运行的 8080 后端，沿用独立输出目录验证：临时 POM 与原 POM 仅 build.directory 不同，保留标准 target/test-classes 形状。原 pom.xml 没有修改，临时 POM 已移除。

后端（工作目录 backend）：

```powershell
.\mvnw.cmd -B -f .advisor-evidence-verification.pom.xml '-Dit.test=*Advisor*IntegrationTest,ScopedBudgetRiskIntegrationTest,CostIntelligenceRunIntegrationTest' verify
```

- 单元/架构/契约：610 项，失败 0、错误 0，1 项 Windows 平台条件跳过。
- 集成：27 项，失败 0、错误 0。是本轮相关集成回归，不是全部 1191 项集成的重新执行。
- package/repackage 完成，BUILD SUCCESS，耗时 1 分 48 秒；使用既有 MinIO 默认值，没有镜像覆盖参数。

Gateway（工作目录 gateway）：

```powershell
.\mvnw.cmd -B '-Dtest=AdvisorInferenceWorkerTest' test
```

12 项通过。Frontend（工作目录 frontend）：

```powershell
npm run test -- --run src/features/advisor
```

2 个文件、3 项通过。没有修改前端产品代码，也未进行浏览器/UI 验收。

新 JAR 使用原 local profile、8099 端口做独立启动检查，健康接口返回 200、UP；检查后通过本轮前台进程会话终止验证实例。原 8080 实例不被替换，源码整理不会自动重启该本地实例。

报告与产物目录：
`C:\Users\32962\AppData\Local\Temp\aicostops-advisor-verification-7d0f5bcf258a4d7f9a96dd8ddcb572c2\target`。
后端日志 `%TEMP%/aicostops-advisor-evidence-verify-20261011.log`；Gateway 日志 `%TEMP%/aicostops-advisor-gateway-test-20261011.log`。

## 下一步

建议下一轮先检查 Advisor 的 profile 更新与内部身份/凭据轮换职责，判断是否值得单独提取应用服务。先保留当前事务、版本冻结和 job/attempt 同时失效的边界，不立即扩展到网关执行或财务对账。

本轮未提交或推送 Git，保留已有报销、成本分析、MinIO 和前端的未提交改动。
