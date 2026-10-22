# 02 — 开发与设计包 (Development)

本目录是开发人员和 AI 代理进行系统日常维护、功能精读、设计查阅与接口对接的权威参考包。

## 目录分层与职责

| 子目录 | 职责与内容 | 说明 |
|---|---|---|
| [`api/`](api/) | OpenAPI 契约、DTO 规范、错误码、前后端协作契约 | 统一收录 `openapi.yaml`, `gateway-openapi.yaml`, `m18-v3-openapi.yaml` |
| [`designs/`](designs/) | 模块详细设计方案 | 包含 `v1/`（历史基础）、`v2/`（网关与扩展）、`maintenance/`（维护期微设计） |
| [`guides/`](guides/) | 源码解读与阅读路线（Walkthrough） | 包含报销样板业务解读、Advisor 模块精读与参考提示词 |
| [`implementation/`](implementation/) | 实施路线、Issue 追踪与环境 Runbook | 历史实施记录与本地引导手册 |

> [!NOTE]
> 生产运维、监控与部署文档已统一收敛至 [`docs/04-operations/`](../04-operations/)。

---

## 按开发任务查文档

### 1. 架构与详细设计查询
- **V1 核心财务与基础设计**：[`designs/v1/`](designs/v1/)（数据模型、状态机、事务幂等并发、权限矩阵、导入设计）
- **V2 网关与多 Provider 运行时**：[`designs/v2/`](designs/v2/)（网关路由、实时计量、预算原子性、混合对账）
- **维护期优化设计**：[`designs/maintenance/`](designs/maintenance/)（成本智能重构、覆盖率历史统计等）

### 2. API / Controller / Frontend 接口
- **契约总览**：[`api/README.md`](api/README.md)
- **Control Plane API**：[`api/openapi.yaml`](api/openapi.yaml)
- **Gateway Data Plane API**：[`api/gateway-openapi.yaml`](api/gateway-openapi.yaml)
- **V3 扩展契约**：[`api/m18-v3-openapi.yaml`](api/m18-v3-openapi.yaml)
- **全局接口约定与错误码**：[`api/01-全局API约定.md`](api/01-全局API约定.md)、[`api/04-错误码幂等并发.md`](api/04-错误码幂等并发.md)

### 3. 业务精读与代码样例
- **报销业务样板源码解读**：[`guides/expense-module-walkthrough.md`](guides/expense-module-walkthrough.md)
- **Advisor 成本优化建议模块**：[`guides/advisor-module-walkthrough.md`](guides/advisor-module-walkthrough.md)

---

## API 协作铁律

```text
HTTP / DTO / Schema  →  api/openapi.yaml & api/gateway-openapi.yaml
Business Semantics   →  designs/
```

任何 API 变更必须在同一 PR 内同步更新契约文件，禁止口头修改接口。
