# 05. 前后端 API 协作规则

## 1. 不允许口头契约

不能出现：

```text
后端：“我临时把 amount 改成 cost 了”
前端：“那我先 any 接一下”
```

所有接口变化必须体现在 Git PR 中。

## 2. 修改顺序

推荐：

```text
Issue / AIC
→ 修改 openapi.yaml
→ Reviewer 先看 Contract
→ Backend / Frontend 并行
→ Contract Test
→ Merge
```

同一个人可以一次 PR 同时改后端和 Contract；另一人基于 PR Review 后开发前端。

## 3. Endpoint / DTO 命名

Backend Controller、OpenAPI、Frontend Type 必须语义一致。

例如统一：

```text
availableAmount
```

不能：

```text
Backend: available
OpenAPI: remainingBudget
Frontend: leftMoney
```

## 4. Frontend Type

禁止长期使用：

```ts
any
Record<string, any>
```

逃避 Contract。

Provider-specific Raw Metadata 除外，但也应该有明确 Boundary Type。

## 5. Error Code

Frontend 分支逻辑基于稳定 `code`：

```text
BUDGET_INSUFFICIENT
VERSION_CONFLICT
PERIOD_CLOSE_BLOCKED
```

不基于中文 `detail` 文本做字符串判断。

## 6. Contract Test

Backend 至少验证：

```text
Path / Method
Request Validation
Response Schema
ProblemDetail
Content-Type
```

如果后续引入 Springdoc/OpenAPI 输出，可在 CI 比较生成 Contract 与 Repo Baseline，防漂移。

在此之前，PR Review + API Integration Test 是主要 Gate。

## 7. Breaking Change

开发期确实需要改 Contract 时：

```text
修改 Issue
修改 openapi.yaml
修改 Markdown
双方 Review
再改代码
```

不要偷偷兼容两套字段长期存在。

## 8. Mock

Frontend 可以在 Backend 未完成时使用 Mock Data，但：

```text
Mock 必须来自 openapi.yaml / DTO Contract
```

不能为了页面方便制造不存在字段。

## 9. Generated Client

V1 初期不强制 OpenAPI Generator。

后续如果引入：

```text
openapi.yaml
→ Generated Type / Client
```

生成结果应可重复，不手改 Generated File。

## 10. PR Checklist

涉及 API 的 PR 必须确认：

```text
[ ] openapi.yaml 已同步
[ ] Endpoint Matrix 已同步（如果 Path/Permission 变化）
[ ] DTO 文档已同步（如果核心字段变化）
[ ] Permission 与 Scope 一致
[ ] Error Code 一致
[ ] Frontend Type/Client 已同步或有明确依赖 Issue
[ ] Contract/Integration Test 已更新
```
