# 07. Redis 详细设计

## 1. 总原则

Redis 在 V1 中是真实使用的基础设施，但不是财务 Truth。

```text
Redis Restart
不能改变
Ledger / Budget / BillingPeriod
```

所有 Key 使用统一 Namespace：

```text
aicostops:v1:<domain>:...
```

每类 Key 都要定义：

```text
Owner
Value Schema
TTL
Invalidation
Failure Policy
```

## 2. Refresh Session

Key：

```text
aicostops:v1:auth:refresh:{sessionId}
```

Hash：

```text
user_id
org_member_id
security_version
current_token_hash
previous_token_hash
previous_valid_until_ms
created_at_ms
last_rotated_at_ms
absolute_expires_at_ms
device_label
```

V1 默认目标：

```text
Access Token: 15 min
Refresh Session: 7 days
Previous-token Race Window: ~10 sec
```

全部可配置。

Cookie：

```text
sessionId.secret
```

Redis 只保存 Hash/HMAC 后的 Secret，不保存可直接复用的 Refresh Token。

## 3. Refresh Rotation

用小型 Lua 保证 Compare + Rotate 原子性。

结果：

```text
ROTATED
RACE
REPLAY
EXPIRED
```

当前 Hash Match：

```text
previous = current
previous_valid_until = now + raceWindow
current = newHash
```

Previous Match 且仍在短 Race Window：

```text
AUTH_REFRESH_RACE
```

前端短暂等待后 Retry once。

旧 Token：

```text
AUTH_REFRESH_REPLAY
```

可以 Revoke Session + Audit。

这类 Lua 只用于 Auth Runtime，不承担 Budget/Ledger。

## 4. Logout / Logout All

当前 Session：

```text
DEL auth:refresh:{sessionId}
```

Logout All：

```text
MySQL security_version++
+
Best-effort Delete Redis Sessions
```

MySQL 的 Security Version 才是 Durable Invalidation Signal。

## 5. Security Version Cache

Key：

```text
aicostops:v1:auth:security:{userId}
```

Value：

```json
{"status":"ACTIVE","securityVersion":8}
```

TTL：

```text
1-5 min
```

Sensitive Mutation 必要时直接回 MySQL Fresh Check。

## 6. Verification Code

```text
aicostops:v1:auth:verify:{purpose}:{targetHash}
```

Value：

```text
code_hash
attempt_count
created_at
```

TTL 目标：

```text
5 min
```

Key 中不用 Raw Email，使用 Target Hash。

## 7. Password Reset

```text
aicostops:v1:auth:reset:{tokenId}
```

Value：

```text
user_id
token_hash
```

TTL：

```text
30 min
```

Single-use。

成功后：

```text
Delete Reset Key
Security Version++
Revoke Refresh Sessions
```

## 8. Login Rate Limit

V1 选择 Fixed Window：

```text
INCR
EXPIRE
```

Key：

```text
aicostops:v1:ratelimit:login:ip:{ipHash}:{windowId}
aicostops:v1:ratelimit:login:account:{accountHash}:{windowId}
```

示例默认：

```text
IP: 20 / 15 min
Account: 8 / 15 min
```

这是项目默认值，不是通用行业标准。

Redis 不可用时 Login 不允许 Silent Fail-open。

V1 Policy：

```text
短 Timeout
→ 503 Auth Service Temporarily Unavailable
```

已持有有效 Access Token 的普通业务请求不因为 Login Rate Limit Redis Down 自动失败。

## 9. Permission Context Cache

Key：

```text
aicostops:v1:iam:user-context:{userId}:sv:{securityVersion}
```

Value：

```json
{
  "roles":[],
  "permissions":[],
  "scopes":[]
}
```

TTL：

```text
~5 min
```

Role/Membership/User Status 变化时：

```text
Security Version Bump
或
Explicit Eviction
```

旧 Version Key 自动不再命中。

## 10. Dashboard Cache

例如：

```text
aicostops:v1:dashboard:project:{projectId}:period:{periodId}:currency:{currency}
aicostops:v1:dashboard:cost-center:{costCenterId}:period:{periodId}:currency:{currency}
aicostops:v1:dashboard:org:period:{periodId}:currency:{currency}
```

TTL：

```text
30-60 sec
```

Ledger Posting 后 Best-effort Delete + Short TTL。

Delete 失败只能导致短暂 Stale，不影响财务 Truth。

## 11. Optional Lookup Cache

只有 Profiling 证明有价值才加：

```text
Provider List
Project Directory
Cost Center
```

禁止到处无脑 `@Cacheable`。

## 12. Failure Policy

| Key 类型 | Redis Down 时 |
|---|---|
| Login Rate Limit | Fail-closed / 503 |
| Refresh Session | Refresh 暂时不可用，现有 Access Token 到期前可继续 |
| Permission Cache | 安全回源 MySQL |
| Dashboard Cache | 回源 MySQL |
| Security Version Cache | Sensitive Action 回源 MySQL |
| Financial Mutation | 正确性不能依赖 Redis |

## 13. Serialization

优先：

```text
String / Hash
明确 Schema
```

不使用 Java Native Serialization。

JSON Payload 如果存在，要考虑版本。

## 14. Metrics

```text
Redis Error / Latency
Refresh Success / Race / Replay
Login Rate Limited
Permission Cache Hit/Miss
Dashboard Cache Hit/Miss
```

## 15. 必须测试

```text
Session Expiry
Rotation
Replay
Cross-tab Race
Logout / Logout All
Rate Limit
Permission Invalidation
Redis Restart
Dashboard Fallback
```
