# 2026-10-10 报销持久层结构整理

## 修改目的与结构

原 `ExpenseClaimMapper` 同时包含报销主表读写、列表摘要与总数、审批流程与动作历史。不同调用方都依赖一个大接口，列表 SQL 与筛选条件也重复写在 Java 注解里。

本轮按实际职责划分，继续使用现有 MyBatis：

```text
backend/src/main/java/com/aicostops/expense/infrastructure/
├── ExpenseClaimMapper.java       # 主表读写、锁、版本条件、关联状态校验
├── ExpenseApprovalMapper.java    # 审批流程读写、锁、追加动作历史
└── ExpenseQueryMapper.java       # 本人/审核列表及总数的方法声明

backend/src/main/resources/mapper/expense/
└── ExpenseQueryMapper.xml        # 摘要关联查询、动态筛选、分页 SQL
```

列表与总数共用 `claimantFilter` 和 `reviewFilter`，摘要共用列与关联片段。SQL 使用 `#{}` 绑定参数，不拼接用户输入；关联仍限定同组织，排序保持原方向及 ID 次序。

XML 的 `namespace` 与接口全名相同，语句 ID 与方法名相同。通过 Backend `application.yml` 中的 `mybatis.mapper-locations: classpath*:/mapper/**/*.xml` 加载，Gateway 配置没有变更。

主表更新、审批状态更新与行锁继续使用原 SQL 注解；写入仍检查组织、状态、版本和影响行数。Mapper 拆分没有改变事务范围、审批锁顺序、HTTP 契约或数据库结构。凭证与归属状态读取保留在报销主表 Mapper，支持既有业务校验。

## 调用方对应关系

| 调用方 | 使用的 Mapper |
| --- | --- |
| 员工创建、提交、取消和财务审核 | 主表 + 审批 |
| 详情组装 | 主表关联状态 + 审批历史 |
| 本人列表 | 列表查询 |
| 本人或审核详情 | 主表读取后委托详情组装 |
| 审核列表 | 列表查询 |
| 凭证上传、下载和入账适配器 | 主表 |

最新阅读链见 [报销模块源码解读](../02-development/expense-module-walkthrough.md) 和 [项目阅读指南](../project-reading-guide.md)。之前的验收记录与历史计划保留当时的描述。

## 索引核对

V10 中已有：

- `idx_expense_claim_org_claimant(org_id, claimant_member_id, created_at, id)`：对应本人筛选与排序字段。
- `idx_expense_claim_org_status(org_id, status, created_at, id)`：对应组织审核状态筛选。
- 审批流程的组织与报销唯一关联，以及动作历史的组织与流程索引。

本轮未新增索引。以上是 SQL 与已有索引的静态核对，不能作为生产性能结论；多状态审核队列可能仍需要排序，是否进一步优化应依据实际数据规模和执行计划。

## 验证

保留上轮的完整报销回归，更新“不逐行读取审批详情和历史”的断言，增加真实数据库测试覆盖：

- SUBMITTED、NEEDS_INFO、APPROVED 筛选及对应总数。
- ALL 队列包含上述三类，排除 DRAFT、POSTED 和其他组织数据。
- 分页顺序与完整队列一致，超出末页返回空列表。
- 本人列表及总数维持本人和组织范围。

使用 Testcontainers 隔离数据库；MinIO 显式覆盖镜像沿用前两轮，不改变默认和生产镜像。在 `backend` 目录复现：

```powershell
.\mvnw.cmd -B '-Daicostops.test.minio-image=cgr.dev/chainguard/minio@sha256:f74600a1a46330cdbda1ef760d17a96bd6e0f4a6f0a2c49792ca3ee7e4c6fa18' '-Dtest=*ArchitectureTest,ExpenseClaimStatusTest,AllocationResponseCodecTest' '-Dit.test=Expense*IntegrationTest,PostingPortIntegrationTest,E2eExpenseMainlineIntegrationTest' verify
```

这里的完整报销主线是认证 MockMvc 后端测试，前端没有修改；本轮没有执行浏览器或真实 Provider 调用验收。

## 本轮结果

- 编译与打包通过，Spring Boot JAR 包含两个新 Mapper 接口及查询 XML。
- 39 项基础与架构测试全部通过，其中架构检查 36 项。
- 70 项集成测试全部通过，无失败或跳过；列表专项测试 4 项，覆盖新 XML 的结果映射、筛选、总数、分页和组织范围。
- 完整报销 HTTP 主线、审批并发、幂等、凭证、归属与入账回归通过。
- 源码解读的 33 个链接和本记录的两个阅读入口均检查存在。
- `git diff --check` 通过。
