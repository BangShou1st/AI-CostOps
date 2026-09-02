# API 开发契约 — 阅读入口

> 这组文件是 Control Plane 与 Gateway Data Plane 的机器可读 API 协作基线。

## 一、机器 Source of Truth 按 Runtime 分离

V2 起存在两个独立 deployable / compatibility surface，因此机器契约也明确分离：

```text
Control Plane HTTP
→ api/openapi.yaml
→ base /api/v1
→ Spring MVC Backend
→ application/problem+json

Gateway Data Plane HTTP
→ api/gateway-openapi.yaml
→ base /v1
→ Spring WebFlux Gateway
→ OpenAI-compatible error envelope
```

**同一个 Endpoint 只能由其中一份 OpenAPI 定义。**

状态机 / 权限 / 事务 / 幂等 / MySQL-Redis ownership / 财务语义：

```text
V1 Control Plane
→ detailed-design/

V2 Gateway
→ ../v2-detailed-design/
```

如果 Markdown 示例与对应 OpenAPI 在字段、类型、Path、Method 上冲突：

> **以对应 Runtime 的 OpenAPI 为机器契约，并在同一个 PR 修正文档。**

如果 OpenAPI 与业务/财务不变量冲突：

> 不能为了迁就 YAML 改业务，先修正 Detailed Design / ADR，再同步 OpenAPI。

---

## 二、Control Plane 开发入口

### Backend 写 Controller / DTO 前

```text
api/openapi.yaml
api/01-全局API约定.md
api/02-接口矩阵.md
api/03-DTO与Schema约定.md
api/04-错误码幂等并发.md
detailed-design/06-permission-matrix.md
对应 V1/V1.1 模块 Detailed Design
```

### Frontend 写 API Client / Query Hook 前

```text
api/openapi.yaml
api/01-全局API约定.md
api/02-接口矩阵.md
api/03-DTO与Schema约定.md
api/05-前后端API协作规则.md
```

Frontend 不能凭页面需要自行发明 Endpoint、字段或错误码。

---

## 三、Gateway Data Plane 开发入口

Gateway endpoint / DTO / error / SSE 开发前必须同时阅读：

```text
api/gateway-openapi.yaml
../v2-detailed-design/README.md
../v2-detailed-design/03-request-state-machine.md
../v2-detailed-design/05-provider-streaming-metering.md
../v2-detailed-design/09-data-api-migration-testing.md
```

Gateway 机器契约的范围是**明确的 OpenAI-compatible subset**，不是 Full OpenAI API conformance。

当前 M11 入口：

```text
POST /v1/chat/completions
GET  /v1/gateway/requests/{requestId}
```

账务安全要求 `POST /v1/chat/completions` 携带：

```text
Authorization: Bearer <AI-CostOps Gateway key>
Idempotency-Key: <1..128 visible ASCII chars>
```

未知/未支持字段必须显式拒绝，不允许为了“看起来兼容”而静默丢弃会改变用户语义的字段。

---

## 四、API Change Rule

### Control Plane 变化

必须同步：

```text
1. openapi.yaml
2. 对应 Controller / DTO
3. Frontend API Type / Client
4. API Contract Test
5. 如果语义变化，再更新对应 Detailed Design
```

### Gateway Data Plane 变化

必须同步：

```text
1. gateway-openapi.yaml
2. Gateway handler / compatibility DTO
3. Provider Adapter compatibility mapping when affected
4. Gateway API contract / integration test
5. 如果涉及状态机、幂等、计费、Streaming、失败恢复，再更新 V2 Detailed Design
```

禁止：

```text
后端/Gateway 先改接口，口头通知调用方
前端/客户端自己猜字段
Gateway 静默 pass-through 未设计的 Provider 字段
开发完再补 OpenAPI
同一 Path 同时出现在两份 OpenAPI
```

---

## 五、OpenAPI-first 的含义

本项目不是要求先建立巨大的 codegen 平台，而是：

> **接口改动必须先或同时更新可审查的机器 Contract。**

如果以后接入 OpenAPI Type Generator，也只能从对应 Runtime 的 YAML 生成，禁止把生成文件反过来作为设计来源。

---

## 六、文档索引

Control Plane：

- `01-全局API约定.md`：ID、Money、时间、分页、认证、版本等。
- `02-接口矩阵.md`：V1 Endpoint 的 Method / Permission / 幂等策略。
- `03-DTO与Schema约定.md`：V1 核心 DTO 的字段与 JSON 约定。
- `04-错误码幂等并发.md`：ProblemDetail、409、Idempotency、Version。
- `05-前后端API协作规则.md`：前后端如何避免接口漂移。
- `openapi.yaml`：Control Plane 机器可读接口基线。

Gateway Data Plane：

- `gateway-openapi.yaml`：Gateway 机器可读接口基线。
- `../v2-detailed-design/`：Gateway 业务、财务、状态机、Streaming、Redis/MySQL、Migration 与 Test Source of Truth。
