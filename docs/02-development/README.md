# 02 — 开发文档包

这部分是**两个人实际写代码时每天查的文档**。

## 开发总顺序

```text
implementation/02-issue-backlog.md
→ 找到当前 AIC Issue

detailed-design/
→ 看对应模块实现契约

api/
→ 如果涉及前后端接口，先看 OpenAPI / DTO / Error

implementation/04-pr-ci-release-process.md
→ 提 PR 前检查
```

## 按开发任务查文档

### Backend / 数据库

```text
detailed-design/01-module-boundaries.md
detailed-design/02-data-model.md
detailed-design/04-transactions-idempotency-concurrency.md
detailed-design/10-error-model-observability.md
```

### API / Controller / Frontend Client

```text
api/README.md
api/openapi.yaml
api/01-全局API约定.md
api/02-接口矩阵.md
api/03-DTO与Schema约定.md
api/04-错误码幂等并发.md
api/05-前后端API协作规则.md
detailed-design/06-permission-matrix.md
```

### IAM

```text
detailed-design/06-permission-matrix.md
detailed-design/07-redis-design.md
api/
```

同时参考蓝图包中的：

```text
architecture/13-iam-auth-design.md
```

### Provider Import

```text
detailed-design/08-provider-import-design.md
detailed-design/02-data-model.md
detailed-design/04-transactions-idempotency-concurrency.md
```

同时参考蓝图包中的 Provider Mapping。

### Budget / Ledger / Reconciliation / Close

```text
detailed-design/02-data-model.md
detailed-design/03-state-machines.md
detailed-design/04-transactions-idempotency-concurrency.md
detailed-design/06-permission-matrix.md
api/
```

### Frontend

```text
detailed-design/09-frontend-information-architecture.md
api/
```

### Repository / Git / Docker

```text
detailed-design/11-git-collaboration-governance.md
detailed-design/13-repository-source-layout.md
detailed-design/14-repository-hygiene-gitignore.md
detailed-design/15-configuration-environments.md
implementation/04-pr-ci-release-process.md
implementation/05-bootstrap-local-development-runbook.md
```

## API 的唯一协作规则

```text
HTTP / DTO / Schema
→ api/openapi.yaml

Business Semantics
→ Detailed Design
```

任何 API Change 必须在同一 PR 同步 Contract，禁止口头改接口。

## PR 前

至少看：

```text
implementation/04-pr-ci-release-process.md
对应 AIC Issue
对应 Detailed Design
api/（如果影响 API）
```
