# 2026-10-10 目录结构与代码职责审查

## 结论

保留根目录的backend、gateway、frontend、docs、deploy、scripts，以及Backend按业务模块组织的方式。当前可读性的主要改进点是少数文件职责过多和公共代码归属不清，集中搬目录并不能解决这些问题。

这是对当前工作区源码的静态审查，包含此前尚未提交的报销整理。没有修改业务代码，没有重新运行测试，也不能由此推断所有模块的业务正确性。

后续落实状态：下列第1、2项已完成，见[前端权限归属与对账表单整理](../../../03-acceptance/maintenance/2026-10/2026-10-10-frontend-structure-refactor.md)。本报告的源码行号是审查时的位置，拆分后的文件职责与测试结果以该完成记录为准。

## 值得处理的事项

优先级表示整理顺序；下列事项是维护性问题，不是已复现的生产故障。

### 1. 公共权限判断放在设置模块中

证据：`frontend/src/features/settings/permissions.ts:3`定义通用hasPermission，但同一文件还包含设置导航和角色范围展示；27个features内文件通过包含settings/permissions的路径引用它。

影响：报销、预算、对账等功能为了权限判断依赖设置模块，新读者容易误以为权限属于设置页面实现。调整设置导航时也会牵涉一个被广泛引用的文件。

建议：只将通用权限判断移到`frontend/src/features/auth/permissions.ts`，设置导航与角色展示保留原处。更新调用方及相关测试，保持权限结果与导航行为不变。不把整个文件搬走。

优先级：下一轮先做，范围明确、改动风险较低。

### 2. 对账详情页承载多种独立操作

证据：`frontend/src/features/reconciliation/ReconciliationCaseDetailPage.tsx:53`起同时维护解决案例、财务调整、证据处置、修正关联等表单状态；110行起集中声明多种mutation；367、385、449行附近有不同Modal，另有GatewayResolutionModal。

影响：修改一个弹窗时，需要穿过其他业务的状态、查询、mutation和页面渲染，理解成本高；不同操作失败后的提示和刷新策略也不容易单独核对。

建议：参照已独立的GatewayResolutionModal，把案例解决、财务调整和证据处置各自拆成有明确输入输出的组件；在确有复用或明显减负时抽取查询/命令hook。页面保留身份、权限和各区域组合。不要一次性搬动所有reconciliation文件。

优先级：公共权限归属整理之后。

### 3. 网关Controller承担了请求执行流程

证据：`gateway/src/main/java/com/aicostops/gateway/web/ChatCompletionController.java:271`起包含流调用、尝试切换、失败处理、计量终结和取消处理；414行的invokeProviderWithFailover及547行的finalizeCancellation也位于HTTP控制器。

影响：HTTP协议处理与上游执行、计量、取消生命周期交织，改一个分支容易需要同时理解多种终结状态。单纯把web重命名成api不会减轻这种耦合。

建议：逐步把上游执行协调移到request下的独立执行类，保留web中的请求大小校验、HTTP响应和SSE编码职责。优先抽取边界清楚的方法组，不合并现有生命周期服务。

优先级：较后。必须以现有流式、非流式、取消、超时、失败切换和用量终结测试保护行为，尤其保持dispatch之后不得重复执行上游的边界。

### 4. 网关财务处理服务与Mapper过于集中

证据：`backend/src/main/java/com/aicostops/reconciliation/application/GatewayFinancialResolutionService.java:183`的事务方法协调证据归属、财务锁和发布；439行起有账单绑定，764行起有账期规则，800行起有调整入账。该类113行还直接依赖gatewaysettlement模块的GatewayReservationSettlementMapper。`reconciliation/infrastructure/HybridReconciliationMapper.java`同时承载关联候选、未决工作、证据与财务处理持久化。

影响：读者必须同时理解长事务、证据语义、预占来源和账本规则；跨模块Mapper依赖暴露另一模块的数据库实现，后续修改时影响范围不够直观。

建议：先提取纯校验规则和绑定结果类型，再按调用场景拆查询与写入Mapper；跨模块的锁定预占访问可由gatewaysettlement提供明确接口。外层事务和锁获取顺序仍集中协调，禁止为了拆类引入新的独立提交边界。

优先级：较后。需要关账、并发、幂等与故障回滚集成测试保护，不能当作简单文件搬迁。

### 5. 成本分析服务仍混合计算、查询与建议状态操作

证据：`backend/src/main/java/com/aicostops/intelligence/application/CostIntelligenceService.java:108`组织分析，214行起生成节约建议，433行起提供结果查询，558行起处理建议确认、忽略和应用。

影响：算法修改、页面查询及建议状态修改落在同一个服务，文件较长且有多条不同的业务阅读路径。此前拆出的CostIntelligenceRunService已经处理任务事务，但并未分离上述职责。

建议：按分析执行、结果查询、建议状态命令分工，复用已有domain算法；保持CostIntelligenceRunService的成功提交和失败记录边界，不再创造一套任务事务。

优先级：有相关维护需求时处理，排在前两项之后。

## 建议保留的组织方式

- Backend的api/application/domain/infrastructure分层已有实际用途，并有ModuleDependencyArchitectureTest等规则约束。简单模块没有domain目录也可以成立，不创建空目录来凑齐四层。
- budget已有BudgetCommandService和BudgetQueryService，Controller主要负责输入输出；不是本轮优先整理对象。BudgetCommitmentCommandService较长，需要按实际职责进一步判断，不能只凭行数拆类。
- attribution保存归属模型、仓储接口与实现，allocation负责归属用例和跨费用来源协调。当前已有架构规则约束两者依赖，先保留边界，在阅读指南解释关系。
- Gateway是独立部署的请求数据通道，采用web/request/provider/persistence等职责目录，与Backend的四层目录无需强行统一。
- 前端api放公共HTTP与错误处理，features内api放业务请求，lib放金额和时间等工具；两类api用途不同。
- 前端benchmark由import.meta.env.DEV条件装配，用于开发对照，不因为名称像测试数据就删除。
- 测试与业务代码相邻便于定位；数据库已发布迁移和历史验收文件保留原位置。

## 下一步

先整理通用权限函数归属，再拆对账详情页的独立表单。每一步保持接口与行为不变，运行相关测试、lint和构建；涉及页面交互变动时通过browser-skill验收。完成这两项后，再依据真实维护需求决定是否处理网关执行和财务服务。无需现在开展全项目目录重排或引入更多Maven模块。
