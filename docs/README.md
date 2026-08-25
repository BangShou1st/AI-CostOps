# AI CostOps 文档导航

当前稳定版本：`v1.0.1`。V1 已完成并冻结；V1 的 RC、验收、benchmark 与 issue backlog 文档作为历史证据保留。V2 进入现有 Realtime AI Gateway 蓝图的详细设计与实施规划阶段。

实际 Git 仓库统一保存三类文档：

```text
docs/
├── 01-blueprint/
├── 02-development/
└── 03-acceptance/
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
