# 2026-10-11 报销、成本分析与 Advisor 联合审查

## 范围与结论

本轮审查现有三个模块的拆分成果，修复状态边界问题，并把已有改动按依赖和模块整理为本地提交。保留其他未提交的前端路由、Provider 样式、对账表单和开发脚本等改动，没有 reset、clean 或推送。分支为 `codex/module-review-20261011`，基线为 `961ddab`。

报销保留组织与主题授权、版本条件更新、幂等和事务边界；查询 XML 的列表与总数共享筛选片段，入账通过端口调用基础设施适配器。成本分析保留分析与派生数据的原子提交，失败状态独立持久化，建议状态变更保留审计事务，作用域预算预测没有改为组织预测回退。Advisor 保留配置版本绑定、凭据轮换、快照指纹和证据格式，Gateway 的 Provider 调用仍在短事务之外。

## 发现并修复的问题

`AdvisorJobLifecycleService.terminalizeAttempt` 原来只用领取 token 限制任务更新，没有核对调用者传入的执行次数是否为任务当前次数。若历史数据中同时存在旧 PENDING 尝试和当前尝试，使用有效 token 与旧次数可能结束任务及旧尝试，却留下当前尝试 PENDING。

现在在状态修改之前读取组织内任务，并要求 `attemptNo == attemptCount`，否则返回既有 `STATE_CONFLICT`；随后仍执行原有 token 条件更新。新增真实 MySQL 参数化集成测试，分别覆盖 COMPLETED 和 FAILED：旧次数被拒绝且任务、两条尝试均保持原状态；当前次数可以正常结束。修复前该测试有 2 个失败，修复后通过。

## 本轮验证

后端使用临时 POM，仅把构建目录移到系统临时目录，执行完整 `verify` 生命周期，避免覆盖用户正在运行的 JAR。默认 MinIO 测试镜像生效，未使用镜像覆盖参数。原有 8080 服务没有停止。

```text
mvnw -B -f .module-review.pom.xml \
  '-Dit.test=*Advisor*IntegrationTest,*Expense*IntegrationTest,PostingPortIntegrationTest,*Intelligence*IntegrationTest,*Savings*IntegrationTest,ScopedBudgetRiskIntegrationTest' verify
```

- 单元及架构：610 项，失败 0、错误 0、跳过 1。
- 三模块及关联入账集成：122 项，失败 0、错误 0。
- 包含 package、Spring Boot repackage 与 failsafe verify。
- 提交快照中的 Gateway Advisor 单元测试：14 项，失败 0、错误 0。

上述为本轮回归范围；历史完整集成与浏览器验收分别见各模块原报告，本轮没有重新执行全量集成或浏览器验收。

## 独立提交快照

报销、成本分析、Advisor 后端依次在临时目录按提交树构建源码和测试源码，确保提交不会依赖后续尚未提交的拆分。成本分析提交中的旧 AdvisorService 仅调整预算风险服务依赖；随后 Advisor 提交删除旧服务并完成拆分，工作目录中没有恢复兼容 facade。

快照首次增量应用时，临时目录被识别为用户目录 Git 仓库的子目录，补丁被跳过。该轮成本分析及 Advisor 编译结果不作为验收证据。临时快照建立独立 Git 仓库后重新应用补丁，对照提交树确认文件内容一致，再清理构建输出重新编译；有效的成本分析快照为 615 个主源码、279 个测试源码，Advisor 为 628 个主源码、280 个测试源码。

本轮日志目录：

```text
C:/Users/32962/AppData/Local/Temp/aicostops-module-review-7a924ba86a1445d391b0839a32f6923a
C:/Users/32962/AppData/Local/Temp/aicostops-review-snapshots-82737d6dacda448d9eaecf9defa6174b
```

前端提交先迁移公共权限函数及所有引用，再提交报销页面和命令钩子，避免报销模块反向依赖 settings。其他页面的提交部分仅调整权限 import；路由懒加载、样式和对账表单改动仍留在工作目录。

公共权限提交的快照通过 build、lint 和 10 文件 47 项权限/设置测试。随后包含报销前端改动的提交快照通过 build、lint 和全量 70 文件 528 项测试；没有使用工作目录中尚未提交的路由、样式或对账修复作为依赖。构建仍提示入口 chunk 较大，路由分包可以在后续独立审查中验证。

## 提交依赖

| 提交 | 内容 |
| --- | --- |
| `cd1d1cd` | 公共分页边界、错误响应测试与契约说明 |
| `c330f8b` | 默认 MinIO 集成测试镜像固定与验证记录 |
| `986e5cd` | 报销查询、审批、入账职责与事务拆分 |
| `77ed92d` | 成本分析、建议命令、预算风险查询拆分 |
| `0eb4993` | Advisor 拆分、当前尝试次数校验及 Gateway 证据组件 |
| `f9c25e8` | 公共权限函数移到 auth，同步调用方 import |
| `4e25837` | 报销金额字符串精度、命令重试键与列表错误状态 |

随后提交报销前端与本报告、三个模块的开发和验收文档。没有创建远程 PR、推送、合并或更新运行中的应用；源码提交与运行实例分别管理。下一步宜独立审查剩余前端路由、对账表单和样式改动，完成对应验证后再整理它们的提交。
