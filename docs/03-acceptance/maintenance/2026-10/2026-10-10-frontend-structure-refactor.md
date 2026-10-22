# 2026-10-10 前端权限归属与对账表单整理

本文记录首次工作区拆分阶段。后续路由、对账重试与样式复核及模块提交见 [2026-10-11 前端收尾](2026-10-11-frontend-review.md)。当前幂等命令钩子已共享到 lib，表单提交期间的锁定行为也已补充；下文行数和 531 项测试为当时记录。

## 范围与完成结果

完成[目录结构审查](../../../02-development/designs/maintenance/2026-10-10-directory-structure-review.md)建议的前两项：公共权限函数归属和对账详情页表单职责。根目录、后端分层与业务协议保持现有结构；本轮没有开展全项目目录重排。

| 文件 | 职责 |
| --- | --- |
| [auth/permissions.ts](../../../../frontend/src/features/auth/permissions.ts) | 通用hasPermission判断；路由、导航和业务调用方均直接引用此处 |
| [settings/permissions.ts](../../../../frontend/src/features/settings/permissions.ts) | 设置导航、角色范围展示；通过认证模块的函数筛选导航 |
| [ReconciliationCaseDetailPage.tsx](../../../../frontend/src/features/reconciliation/ReconciliationCaseDetailPage.tsx) | 权限、详情与证据查询、页面组合、弹窗开关、成功后的查询刷新、统一错误提示 |
| [ResolveCaseModal.tsx](../../../../frontend/src/features/reconciliation/components/ResolveCaseModal.tsx) | 解决案例的输入、校验、提交与成功重置；向页面同步提交状态 |
| [CaseAdjustmentModal.tsx](../../../../frontend/src/features/reconciliation/components/CaseAdjustmentModal.tsx) | 调整账期与目标选择、精确金额匹配、显式分配行、提交和重置 |
| [ChargeDispositionModal.tsx](../../../../frontend/src/features/reconciliation/components/ChargeDispositionModal.tsx) | 单条费用处置的输入、提交与重置 |

详情页由493行降到350行。三个组件分别48、123、69行，页面不再同时维护全部表单字段及命令请求。已有GatewayResolutionModal仍保留其职责。

三个表单通过onSuccess请求页面刷新，通过onError保持统一错误入口；发起新的表单命令时清理上一条表单错误。失败保留输入，成功关闭并重置。费用处置关闭或成功后恢复默认RECONCILIATION_EVIDENCE选项，避免下一次打开继承上一次选择。

## 自动验证

在frontend目录执行：

```text
npm run test -- --run
npm run lint
npm run build
```

70个测试文件、531项测试通过；lint通过；TypeScript检查及生产构建通过。新增3项页面回归，验证：

- 案例解决的必填限制、请求内容及成功后的案例状态刷新。
- CASE_FULL严格匹配8位金额字符串，提交显式分配目标与行金额，成功后刷新证据。
- 费用处置失败后保留输入并显示问题，重试成功后关闭与刷新证据。

既有测试同时覆盖权限不足时隐藏财务操作、加载到详情的Hooks顺序、证据分页和网关财务弹窗。共享权限函数移动的调用方通过全量测试和构建检查；源码搜索确认只保留auth中的函数定义，业务调用方不再从settings导入hasPermission。

首次基线专项运行有一个既有网关证据测试超过默认5秒时限；原时限复跑21项全部通过，随后包含新增用例的全量531项也通过，未放宽测试超时。临时日志：aicostops-structure-all-tests.log、aicostops-structure-lint.log、aicostops-structure-build.log，保存在本机临时目录。

## 浏览器验证与限制

使用browser-skill驱动真实Edge，后台会话aibz，捕获structure-refactor-20261010。

实际登录、设置导航与对账列表连接本地Backend。测试库没有对账运行；对账详情采用会话内7条mock规则，合成案例900001及其运行、证据和3种POST响应。账期1和成本中心1选项来自现有本地API。合成案例的处理请求均被规则拦截，不能据此声称完成真实后端财务验收。

- CASE_FULL弹窗空表单不可提交；选择OPEN账期1、COST_CENTER目标1，填1.00000000仍不可提交，改为2.00000000后可提交。捕获请求n138确认总额与分配行同为2.00000000；成功后弹窗关闭。
- 单条费用处置空表单不可提交；填原因和说明后提交。捕获n142确认只处理chargeFactId=900001、RECONCILIATION_EVIDENCE；成功后弹窗关闭。
- 案例解决空表单不可提交；填原因和说明后提交。捕获n146确认reasonCode与resolutionNote正确；成功后弹窗关闭。合成GET仍返回原案例，真实状态刷新由新增自动测试验证。
- 控制台未记录网站来源的JavaScript错误；三个加载错误来自chrome-extension://invalid/。解决按钮的退出动画留下不可见loading图标，HTML确认opacity=0、width=0且按钮无loading禁用状态。

复查完成后回到连接真实Backend的对账列表，停止捕获以清除规则，导出aicostops-structure-browser-20261010.json至本机临时目录，关闭验收会话。临时浏览器规则与证据不纳入仓库。本轮仅修改前端，未重跑后端测试。

## 建议的下一步

先按上面的文件职责理解此次拆分。未来维护成本分析或网关时再处理审查中的其余事项，本轮完成这两项后停止，不继续扩展重构。
