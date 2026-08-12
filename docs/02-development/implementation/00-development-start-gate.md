# 00. 开发启动门禁

## 1. 启动条件

开发开始前，两名开发者一次性审查：

```text
V0.2 Architecture Baseline
+
V1 Detailed Design 1.1
+
V1 Implementation Plan 1.0
```

审查的目标不是把未来每个类名都锁死，而是确认系统实现契约。

## 2. 必须明确同意的事项

```text
[ ] V1 产品范围与 Non-goals
[ ] MySQL / Redis Truth Boundary
[ ] Budget 公式与超预算行为
[ ] Immutable Ledger / Correction
[ ] BillingPeriod Close
[ ] Provider Evidence 真实性边界
[ ] 模块依赖方向
[ ] 主数据库模型
[ ] Access JWT / Refresh Session
[ ] Permission + Data Scope
[ ] REST API 形态
[ ] Monorepo / 源码目录
[ ] Git tracked / ignored 文件
[ ] PR / Review / CI 流程
[ ] M0-M8 与双人分工
```

## 3. 审查结果

### ACCEPT

直接进入：

```text
创建 Milestones
→ 创建 M0 Issues
→ 开发
```

### ACCEPT WITH CHANGES

只修改明确列出的具体问题，修改完成后立即进入 M0。

### DESIGN CHANGE REQUIRED

只在发现真实架构矛盾时使用，例如：

```text
Budget 无法表达业务
Ledger 事务无法保证不变量
Provider 证据与 Canonical Model 冲突
Auth 存在安全漏洞
模块依赖不可接受地循环
```

不要因为措辞、排版或个人命名偏好阻塞开发。

## 4. 防止设计漂移

设计冻结后，任何实现 PR 都不能偷偷重构系统基本原则。

如果 PR 需要改变架构：

```text
Issue
→ ADR / Design Update
→ Review
→ Implementation
```

## 5. 开发启动顺序

```text
1. 创建 GitHub Milestones
2. 创建 M0 Issues
3. 分配 Owner / Reviewer
4. 两人 Clone
5. 创建短生命周期 Branch
6. Bootstrap PR
7. CI Check 名称稳定
8. 开启 Required Status Checks
9. 继续 M1+
```

## 6. GitHub Milestones

```text
M0 Repository Foundation
M1 Identity & Organization
M2 Evidence & Import
M3 Canonical Cost & Attribution
M4 Expense & Budget
M5 Immutable Ledger
M6 Reconciliation & Close
M7 Workbench & Integration
M8 Hardening & Release
```

Milestone 是工程阶段，不代表承诺日期。

## 7. Issue Definition of Ready

开始某个 Issue 前：

```text
设计依据已存在
依赖已合并，或已有稳定 Port/Mock
Owner 已确定
Reviewer 已确定
Acceptance Criteria 清楚
需要什么测试已经明确
```

## 8. Issue Definition of Done

除非 Issue 明确例外：

```text
代码完成
必要测试完成并通过
没有违反 INV-* 不变量
Schema 变更包含 Flyway
API/设计变化已同步文档
没有 Secret / Generated Data
CI 通过
Peer Review 通过
Conversation 全部 Resolve
Squash Merge
PR 自动关闭 Issue
```
