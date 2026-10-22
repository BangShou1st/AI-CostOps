# 2026-10-10 报销模块结构整理

## 问题与结果

查询、员工操作、审核和凭证上传服务各自复制详情组装逻辑；三个写入服务还复制事务和死锁重试。审核方法传递多个可错配的前后状态，报销侧和账本侧入账实现使用相同类名。

本轮集中重复职责，并保留已有 HTTP 契约、SQL、权限检查、版本条件、幂等顺序和锁顺序：

| 修改 | 作用与边界 |
| --- | --- |
| 新增 `ExpenseDetailAssembler` | 四个服务共用详情组装；授权仍在调用方；列表继续查摘要 |
| 新增 `ExpenseCommandTransactions` | 三个写入服务共用事务及死锁重试；保持 REQUIRED 传播方式，最多三次尝试 |
| 审核服务新增内部 `ReviewAction` | 一个动作集中对应报销目标状态和审批目标状态；减少长参数列表 |
| `expense.application.ExpensePostingService` 移为 `expense.infrastructure.ExpensePostingAdapter` | 名称和目录明确实现报销提供给账本的 Port；保留 Spring Bean 名 `expensePostingService` |
| 移除上传服务未使用的 `ExpenseResponseCodec` 依赖 | 减少无关依赖；文件存储仍在报销事务与重试外 |
| 更新端口测试的适配器引用 | 跟随类移动，原断言不变 |

账本侧 `ledger.application.ExpensePostingService` 仍负责整次入账编排。
员工创建、编辑、提交、取消继续在一个用例服务中表达；没有为了缩短文件把每个动作拆成独立类。

学习入口见 [报销模块源码解读与 Tlias 对照](../../../02-development/guides/expense-module-walkthrough.md)。其中列出真实源码与测试入口，已检查链接目标存在。Tlias 只作本地静态对照，本轮没有运行它。

## 验证方法

使用 Testcontainers 的隔离 MySQL 8.4、Redis、MinIO；沿用上一轮记录的测试镜像显式覆盖，不改变默认或生产镜像。在 `backend` 目录执行：

```powershell
.\mvnw.cmd -B '-Daicostops.test.minio-image=cgr.dev/chainguard/minio@sha256:f74600a1a46330cdbda1ef760d17a96bd6e0f4a6f0a2c49792ca3ee7e4c6fa18' '-Dtest=*ArchitectureTest,ExpenseClaimStatusTest,AllocationResponseCodecTest' '-Dit.test=Expense*IntegrationTest,PostingPortIntegrationTest,E2eExpenseMainlineIntegrationTest' verify
```

新加 `ExpenseCommandTransactionsIntegrationTest` 没有外层测试事务，用真实数据库提交边界验证：

- 注入死锁异常后，首次写入回滚，第二次尝试可正常插入同一唯一键并提交。
- 三次尝试均失败后，没有部分写入。
- 普通异常回滚且不自动重试。

这里注入死锁异常是为了验证重试的事务边界，不声称复现了真实双连接死锁。
完整报销主线由已有 `E2eExpenseMainlineIntegrationTest` 验证认证 HTTP、提交、审核、归属、入账、对账和关账。
这是 MockMvc 后端测试，不是浏览器或真实 Provider 验收；本轮没有修改前端。

## 本轮结果

- 编译通过。
- 39 项基础与架构测试通过，其中架构检查 36 项。
- 69 项集成测试通过，无失败或跳过；包含三项新事务边界测试、完整报销 HTTP 主线和入账端口测试。
- 源码解读的 30 个链接目标均存在，维护记录的学习入口存在。
- `git diff --check` 通过。

## 兼容性说明

HTTP 请求、响应和数据库结构没有变更。内部类路径及构造器依赖改变，仓库内直接引用已更新，历史实施计划保留原路径作为当时的记录。
事务执行器沿用原传播方式：若调用方已有事务，将参与该事务，不能把它视为强制开启独立事务的入口。
