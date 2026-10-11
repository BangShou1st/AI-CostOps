# AI CostOps 文档导航

当前稳定版本：`v3.0.0`，开发已完成，进入维护阶段。V1/V2 的设计、计划和验收记录保留为历史证据；不能把其中的阶段状态解释为当前待办。当前状态以根 [README](../README.md) 和 [PROJECT_CONTEXT](../PROJECT_CONTEXT.md) 为入口。

成本分析整理：[职责与事务设计](02-development/2026-10-10-cost-intelligence-refactor-design.md)、[交给其他 AI 的执行提示词](02-development/prompts/2026-10-10-cost-intelligence-refactor-prompt.md)、[实施与验证结果](03-acceptance/2026-10-10-cost-intelligence-refactor.md)。

成本分析收尾：[补测与打包复核](03-acceptance/2026-10-11-cost-intelligence-review.md)、[MinIO 默认测试镜像](03-acceptance/2026-10-11-minio-test-image.md)。

三个模块收尾：[联合审查、边界修复与模块提交](03-acceptance/2026-10-11-cross-module-review.md)。

Advisor 维护：[模块阅读与事务边界](02-development/advisor-module-walkthrough.md)、[完整整理与验证](03-acceptance/2026-10-11-advisor-module-refactor.md)、[第一阶段证据整理](03-acceptance/2026-10-11-advisor-evidence-refactor.md)。

报销业务样板：[从 Tlias 到 AI-CostOps 的源码解读](02-development/expense-module-walkthrough.md)，包含目录职责、状态流转、事务、幂等、并发和入账的完整阅读路线。

报销验证结果：[可靠性与可读性收尾](03-acceptance/2026-10-10-expense-reliability.md)、[真实浏览器主流程](03-acceptance/2026-10-10-expense-browser-uat.md)。学习时先按源码解读阅读，再用测试与验收记录核对设计边界。

当前 V3 入口：[设计规格](superpowers/specs/2026-09-10-m17-v3-model-provider-hub-cost-intelligence-design.md)、[后端契约](03-acceptance/m18-backend-contract-freeze.md)、[V3 OpenAPI](03-acceptance/m18-openapi.yaml)、[运维手册](04-operations/v3-operational-runbook.md)。V1/V2 接口入口仍在 `02-development/api/`，阅读时结合上述 V3 增量契约。

文档按设计、实现、验收、运维组织，同时保留历史工作记录：

```text
docs/
├── 01-blueprint/
├── 02-development/
├── 03-acceptance/
├── 04-operations/
└── superpowers/          # 历史设计规格与实施计划
```

## 什么时候看哪一类

### 第一次理解项目 / 要改架构

```text
01-blueprint
```

回答：

```text
为什么这样设计？
V1 边界是什么？
V2 产品方向是什么？
领域概念是什么？
Provider 证据是什么？
为什么选 MySQL / MyBatis / Redis / React？
```

V2 产品入口：

```text
01-blueprint/product/05-product-scope.md
01-blueprint/product/11-roadmap.md
```

### 正在开发

```text
02-development
```

回答：

```text
表怎么建？
状态怎么转？
事务怎么锁？
API 怎么定义？
权限是什么？
Redis Key 怎么设计？
目录怎么放？
Issue 怎么拆？
```

V1 的 `02-development/implementation/02-issue-backlog.md` 是冻结的 AIC-001～AIC-073 历史实施计划，不再作为当前待办解释。

### 正在 Review / 测试 / 发布

```text
03-acceptance
```

回答：

```text
这个 PR 怎么验？
这个 Milestone 怎么证明完成？
V1 哪些测试必须跑？
性能结果怎么记录？
V1 最终是如何完成验收与发布的？
```

## 最重要的开发入口

```text
02-development/implementation/02-issue-backlog.md   # V1 frozen history
02-development/detailed-design/
02-development/api/
```

## 最重要的验收入口

```text
03-acceptance/01-PR验收清单.md
03-acceptance/02-Milestone验收证据矩阵.md
03-acceptance/implementation/06-v1-release-acceptance.md
03-acceptance/aic-073-final-human-acceptance.md
```

## V1 Release Outcome

```text
AIC-001 ~ AIC-073 = COMPLETE
AIC-073 = ACCEPTED
v1.0.0 → 982d06a0e9ec844ea687ed746d6b9d8f39d86686
v1.0.1 → b96be614e2d843c101add49fe6daffb9d2343a56
V1 = COMPLETE / FROZEN
```
