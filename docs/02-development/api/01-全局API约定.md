# 01. 全局 API 约定

## Base Path

```text
/api/v1
```

部署采用 Same-origin：

```text
Browser
→ Nginx
→ /api/v1
→ Spring Boot
```

## ID

MySQL：

```text
BIGINT
```

HTTP JSON：

```json
{"id":"123456789012345678"}
```

**所有 ID 使用 String。**

原因：JavaScript `Number` 不能安全表达全部 BIGINT。

## Money

```json
{
  "amount":"1535.12800000",
  "currency":"CNY"
}
```

规则：

```text
Amount = Decimal String
Currency = ISO-like 3-letter Code
Backend = BigDecimal
MySQL = DECIMAL(20,8)
```

禁止 JSON Number 表示财务金额。

## Usage Quantity

需要传输 Token/Usage Quantity 时也使用 Decimal String：

```json
{"quantity":"52792.00000000","unit":"TOKEN"}
```

## Time

Instant：

```text
UTC ISO-8601
2026-08-12T04:20:30.123456Z
```

BillingPeriod：

```text
startDate / endDate
YYYY-MM-DD
[startDate,endDate)
```

## Pagination

Request：

```text
?page=0&size=50
```

默认：

```text
page = 0
size = 50
max size = 200
```

Response：

```json
{
  "items":[],
  "page":0,
  "size":50,
  "totalElements":0,
  "totalPages":0
}
```

## Sort / Filter

Filter 使用明确 Query Parameter。

禁止把复杂 SQL-like Expression 暴露给前端。

Sort 只允许 Server 白名单字段，例如：

```text
?sort=postedAt,desc
```

非法字段：

```text
400 INVALID_FILTER
```

## Authentication

业务 API：

```http
Authorization: Bearer <access-jwt>
```

Refresh Token：

```text
HttpOnly Cookie
Secure（非本地开发）
SameSite=Strict
Path=/api/v1/auth
```

Refresh Token 不返回给 JavaScript。

## Content Type

JSON：

```text
application/json
```

Error：

```text
application/problem+json
```

File Upload：

```text
multipart/form-data
```

Evidence Download 通过受权限控制的 Endpoint，不暴露永久 Public URL。

## API Version

V1 使用 URL Version：

```text
/api/v1
```

实现期不做 `/api/v2`。

Breaking Change 先修改设计，再决定是否真的需要新 Version。

## Null / Missing

原则：

```text
Missing = 调用方未提供
null = Contract 明确允许无值
```

后端不要把空字符串、`0`、`null` 混成同一语义。

## Boolean

只使用：

```json
true
false
```

不使用：

```text
0/1
Y/N
yes/no
```

## Enum

HTTP 使用稳定大写 Code：

```text
OPEN
CLOSING
CLOSED
```

前端显示中文 Label，但不能把中文展示文本提交回 API 作为状态值。

## Optimistic Version

Editable Resource：

```json
{
  "expectedVersion":4
}
```

冲突：

```text
409 VERSION_CONFLICT
```

Financial Counter 不只依赖 Version，仍使用 MySQL Atomic SQL / Lock。

## Trace

Response Header / ProblemDetail 中包含 `traceId`，用于两个人联调定位问题。
