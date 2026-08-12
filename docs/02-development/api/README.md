# API 开发契约 — 阅读入口

> 这组文件是前后端并行开发时的 API 协作基线。

## 一、什么是唯一 Source of Truth

接口分两层约束：

```text
HTTP Path / Method / Request / Response / Schema
→ api/openapi.yaml 为机器可读 Source of Truth

状态机 / 权限 / 事务 / 幂等语义
→ Detailed Design 为业务 Source of Truth
```

如果 Markdown 示例与 `openapi.yaml` 在字段、类型、Path、Method 上冲突：

> **以 `openapi.yaml` 为准，并在同一个 PR 修正文档。**

如果 `openapi.yaml` 与业务不变量冲突：

> 不能为了迁就 YAML 改业务，先修改设计/ADR，再同步 OpenAPI。

## 二、开发时必须看哪些文件

### 后端写 Controller / DTO 前

```text
api/openapi.yaml
api/01-全局API约定.md
api/02-接口矩阵.md
api/03-DTO与Schema约定.md
api/04-错误码幂等并发.md
detailed-design/06-permission-matrix.md
对应模块 Detailed Design
```

### 前端写 API Client / Query Hook 前

```text
api/openapi.yaml
api/01-全局API约定.md
api/02-接口矩阵.md
api/03-DTO与Schema约定.md
api/05-前后端API协作规则.md
```

前端不能凭页面需要自行发明 Endpoint、字段或错误码。

## 三、API Change Rule

任何影响接口的 PR 必须同步：

```text
1. openapi.yaml
2. 对应 Controller / DTO
3. Frontend API Type / Client
4. API Contract Test
5. 如果语义变化，再更新 Detailed Design
```

禁止：

```text
后端先改接口，口头通知前端
前端自己猜字段
同一个字段在两个页面定义两种 Type
开发完再补 OpenAPI
```

## 四、OpenAPI-first 的含义

本项目不是要求“先写一套巨大的自动生成系统”，而是：

> **接口改动必须先或同时更新可审查的 API Contract。**

M0/M1 可以先手写 TypeScript 类型；如果后续接入 OpenAPI Type Generator，也必须由 `openapi.yaml` 生成，禁止把生成文件反过来作为设计来源。

## 五、文档

- `01-全局API约定.md`：ID、Money、时间、分页、认证、版本等。
- `02-接口矩阵.md`：所有 V1 Endpoint 的 Method / Permission / 幂等策略。
- `03-DTO与Schema约定.md`：核心 DTO 的字段与 JSON 约定。
- `04-错误码幂等并发.md`：ProblemDetail、409、Idempotency-Key、Version。
- `05-前后端API协作规则.md`：两个人如何避免接口漂移。
- `openapi.yaml`：机器可读接口基线。
