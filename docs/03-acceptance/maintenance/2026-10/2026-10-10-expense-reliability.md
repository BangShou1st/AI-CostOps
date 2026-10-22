# 2026-10-10 报销模块可靠性与可读性收尾

目标：在已有真实浏览器主流程基础上，核对费用申请、凭证、审批、分摊和入账的失败边界，修复实际缺口，形成可跟踪源码和测试的学习入口。保持现有HTTP契约、数据库迁移和MyBatis，不扩展其他业务模块。

## 本轮修改

- 报销表单使用十进制字符串和精确8位计算，移除parseFloat/toFixed及偷偷截断小数的输入配置；支持现有后端负数费用协议，校验12位整数、8位小数及币种格式。
- 员工和财务详情查询失败时显示问题和重试，不再空白；分摊读取失败或未完成时不呈现可编辑的新草稿与入账按钮。
- 版本冲突提供刷新详情入口；新版本重建表单，避免旧输入与新版本号组合。
- 创建、提交、取消及审核共用useExpenseCommand，失败重试复用同一内容的原幂等键，重复点击共享同一请求。
- 状态展示集中在presentation.ts，用ExpenseClaimStatus限定完整映射。
- 上传凭证在存储前检查已过期版本，同时保留事务内锁定后的检查。
- 新增真实数据库审计故障回滚、关账限制、并发入账、分摊目标停用及无效上传测试；加强越权下载测试，目标报销确实带AVAILABLE凭证。

## 要求与证据对应

| 要求 | 主要证据 |
| --- | --- |
| 本人范围、组织隔离 | ExpenseOwnLifecycleIntegrationTest、ExpenseApiIntegrationTest、ExpenseEvidenceIntegrationTest、ExpenseSummaryQueryIntegrationTest |
| 员工不能越权审核或分摊 | ExpenseApiIntegrationTest、ExpenseAllocationFinanceOnlyIntegrationTest、ExpenseAllocationEligibilityIntegrationTest；既有浏览器403记录 |
| 版本冲突不能覆盖新数据 | ExpenseOwnLifecycleIntegrationTest、ExpenseApiIntegrationTest、ExpenseEvidenceIntegrationTest；ExpensePages刷新表单回归测试 |
| 提交、审核幂等和内容冲突 | ExpenseIdempotencyIntegrationTest；useExpenseCommand丢失响应和重复点击测试 |
| 并发审核与确认 | ExpenseReviewConcurrencyIntegrationTest、ExpenseAllocationUniqueConfirmIntegrationTest |
| 并发入账不重复记账或增加预算 | ExpensePostingIntegrationTest同时检查发布、分录、预算、版本、审计 |
| 分摊金额、币种、目标必须有效 | ExpenseAllocationConfirmIntegrationTest、ExpenseAllocationEligibilityIntegrationTest |
| CLOSING/CLOSED禁止首次批准和入账 | ExpenseFailureAtomicityIntegrationTest、ExpensePostingIntegrationTest、BillingPeriodOpenGuardIntegrationTest |
| 关账后的已成功入账重放 | ExpensePostingIntegrationTest、ExpensePostingApiIntegrationTest |
| 失败不能留下部分数据库写入 | ExpenseFailureAtomicityIntegrationTest；ExpenseCommandTransactionsIntegrationTest |
| 金额精度不丢失 | ExpenseForm.test.tsx；现有money测试、ExpenseApiIntegrationTest |
| 加载失败明确可恢复 | ExpensePages.test.tsx、ExpenseLists.test.tsx |
| 主流程确实能运行 | E2eExpenseMainlineIntegrationTest；既有真实浏览器记录2026-10-10-expense-browser-uat.md |
| 可以按源码读懂设计 | expense-module-walkthrough.md、project-reading-guide.md，链接须核对存在 |

## 执行命令

Backend（PowerShell，在backend目录）：

```powershell
.\mvnw.cmd -B '-Daicostops.test.minio-image=cgr.dev/chainguard/minio@sha256:f74600a1a46330cdbda1ef760d17a96bd6e0f4a6f0a2c49792ca3ee7e4c6fa18' '-Dtest=*ArchitectureTest,ExpenseClaimStatusTest,AllocationResponseCodecTest' '-Dit.test=Expense*IntegrationTest,PostingPortIntegrationTest,E2eExpenseMainlineIntegrationTest,BillingPeriodOpenGuardIntegrationTest' verify
```

MinIO覆盖仅用于本机测试镜像；默认镜像及生产配置不变。测试需要Docker；Windows上运行中的本地Backend会占用打包JAR，打包前停止该验收进程。

Frontend（在frontend目录）：`npm run test -- --run`、`npm run lint`、`npm run build`。

实际结果：后端39项基础与架构测试、90项集成测试通过，无失败、无跳过。加强越权凭证下载用例后，单独重跑ExpenseEvidenceIntegrationTest，6项通过（包含在上述90项中，不重复计数）。前端全量70个测试文件、528项测试通过；lint和生产构建通过。

真实Edge页面复查使用browser-skill，会话vayv，捕获expense-reliability-final，无请求模拟或改写：

- 财务管理员从“我的报销”访问另一员工的报销1时，返回404，页面显示“报销详情加载失败”及重试入口，没有展示他人金额。
- 创建测试草稿2，输入123456789012.12345678，页面与数据库均保留原值，版本v0。
- 输入1.123456789并点击保存，显示精度校验错误；捕获中没有该草稿的PUT请求，版本仍为v0。
- 改为0.00000001并保存，页面和数据库均为0.00000001，DRAFT，v1。未提交、审批或入账该边界测试草稿。
- 原主流程报销1仍为POSTED、105.00000000 CNY、v8。

证据导出到本机临时目录aicostops-expense-reliability-browser-20261010.json；捕获停止并导出后关闭验收会话。测试日志同目录：aicostops-expense-final-backend-tests.log、aicostops-expense-final-evidence-tests.log、aicostops-expense-final-all-frontend-tests.log、aicostops-expense-final-lint.log、aicostops-expense-final-build.log。临时证据不纳入仓库。

## 学习顺序与实际限制

从[源码解读](../../../02-development/guides/expense-module-walkthrough.md)的创建链开始，依次看凭证、提交、审核、分摊和入账。已整理的后端目录是api/application/domain/infrastructure，Mapper按主表、审批、列表查询分工；前端按页面、components、api、hooks和状态展示分工。

“可靠”指上表的现有报销协议及已验证边界，不代表任意故障都能自动恢复。凭证存储与数据库没有分布式事务；浏览器页面刷新后不保留未确认创建请求的幂等键，离开页面后应先核对本人列表；已有确认归属是历史事实，不因目标后来停用而自动重分摊。公网供应商调用、真实付款和生产性能不属于本轮报销验证。
