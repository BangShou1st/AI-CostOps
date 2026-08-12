# AI CostOps 文档导航

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
领域概念是什么？
Provider 证据是什么？
为什么选 MySQL / MyBatis / Redis / React？
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
什么时候可以 tag v1.0.0？
```

## 最重要的开发入口

```text
02-development/implementation/02-issue-backlog.md
02-development/detailed-design/
02-development/api/
```

## 最重要的验收入口

```text
03-acceptance/01-PR验收清单.md
03-acceptance/02-Milestone验收证据矩阵.md
03-acceptance/implementation/06-v1-release-acceptance.md
```
