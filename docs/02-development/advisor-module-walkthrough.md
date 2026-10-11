# Advisor 模块阅读与维护

Advisor 将确定性成本证据交给受治理的 Gateway 执行，模型只返回受限的解释文本。后端拥有配置、授权、快照和任务 API，Gateway 拥有执行流程；两者通过数据库中的任务、尝试和不可变快照衔接，不跨 Maven 模块调用 Java 服务。

## 后端阅读顺序

| 入口或组件 | 维护职责 |
| --- | --- |
| advisor/api/AdvisorController | 原有 profile、请求解释、读取解释、重试的 5 个映射；只做 HTTP 输入与应用服务调用 |
| AdvisorProfileService | 组织授权、配置校验、版本替换、凭据轮换协调、旧任务失效及审计 |
| AdvisorCredentialRotator | 新版本绑定内部凭据、模型授权、前任凭据撤销；参加配置入口事务 |
| AdvisorJobCommandService | 新任务与显式重试；绑定原始配置版本，冻结证据 |
| AdvisorJobQueryService | 按组织读任务、尝试与最新解释；构造 HTTP 响应 |
| AdvisorJobLifecycleService | 领取与 fencing token、网关联接、终结与恢复的短事务 |
| AdvisorEvidenceAssembler | ANOMALY、FORECAST、SAVINGS、BUDGET_RISK 的服务端证据 |
| AdvisorEvidenceSnapshotSerializer / AdvisorEvidence | JSON 快照、金额字符串、引用 ID 与指纹 |
| AdvisorOutputValidator | 后端解释持久化前的输出边界检查 |
| AdvisorRequests / AdvisorReadModels / AdvisorJobClaim | 输入、响应与 worker 领取结果；不依赖 Controller 嵌套类型 |

持久化按 Profile、Job、Evidence 分成三个 Mapper，查询投影放在 AdvisorRows。Profile Mapper 包含与配置绑定紧密相关的内部凭据 SQL，Job Mapper 包含任务与尝试的共同状态操作，Evidence Mapper 包含组织内主题读取与快照写入。无需再为每一张表创建一层 Repository 转发。

## 三个必须一起提交的边界

1. 配置更新：原配置退休、新配置插入、新内部凭据与模型授权、旧凭据撤销、旧任务与尝试失效、审计。
2. 请求解释：任务插入、不可变证据快照、首次尝试、审计。重试追加新尝试，保留首次冻结证据与既有 Gateway 历史。
3. worker 终结：解释写入、任务终态、当前尝试终态与相应审计。受影响行数不符合预期时整体回滚。

辅助组件不创建 REQUIRES_NEW 事务。Mapper 的 LAST_INSERT_ID 读取紧随自己的插入，在外层同一事务连接中执行。HTTP 的组织授权仍在 public 应用入口执行；包内证据与查询辅助方法只供已确认组织的调用方使用。

## Gateway 阅读顺序

AdvisorInferenceWorker 保留任务领取、版本与凭据核对、冻结快照指纹校验、正常 Gateway 治理链执行、用量与结算、失败分类和已关联执行恢复。Provider I/O 位于领取及状态写入的短事务之外；已有关联执行的任务继续收敛，避免重复派发。

AdvisorSnapshotPromptBuilder 负责冻结证据到提示词的转换与事实引用集合，预算风险使用快照中的预计算结果。AdvisorNarrativeValidator 限制字段、文本长度与引用，不接受模型输出金额字段。两个组件都不写数据库、不创建事务。

Gateway 的 AdvisorJobMapper 是这个执行流程的持久化适配器，与后端 Mapper 分属不同进程和模块。保留完整执行流程在 worker 中，避免把一次受治理调用拆成若干难以追踪的转发服务。

## 前端与测试

前端 advisor/api 已将请求、类型和 query key 分开，AdvisorPage 负责一个完整页面。此次后端职责整理保持 URL、响应字段、轮询和重试契约，页面继续保留。

维护时优先运行 Advisor 集成测试，核对外组织拒绝、配置版本冻结、内部凭据轮换、审计故障回滚、不可变证据与显式重试。Gateway worker 测试核对半联接回滚、证据篡改、未知引用、超时结算和禁止重复派发；证据协议测试核对预算预占、预测、金额 scale 及冻结风险。

Gateway 集成测试直接应用后端 V1～V27 迁移；生产迁移仍由后端 Flyway 唯一负责。修改后端迁移时应同步检查 Gateway 测试支持中的迁移清单。
