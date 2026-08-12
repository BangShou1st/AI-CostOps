# 13. IAM / Authentication 设计

## 1. 目标

IAM 只解决 CostOps 需要的身份与权限问题：

```text
谁在访问？
属于哪个组织/项目？
能看到哪些成本？
谁能审批？
谁能关账？
谁能改归属规则？
```

## 2. 持久化边界

### MySQL — Truth

```text
user
credential
organization
team
project
membership
role
permission
role_permission
user_role
data_scope binding
invitation metadata
security_version
account_status
```

### Redis — Runtime

```text
refresh sessions
verification code
password reset token
login failure counters
rate limits
permission context cache
revocation/version cache
```

## 3. 认证模型

### Access Token

```text
JWT
short-lived: recommended 15-30 minutes
```

包含最少必要 claims：

```text
sub/user_id
security/session version
jti
iat
exp
```

不把大量 Project/Permission ID 全塞 JWT。

### Refresh Token

客户端持有随机高熵 token。

Redis 只存：

```text
token hash
session metadata
TTL
```

采用 rotation：

```text
refresh A
→ validate
→ replace A
→ issue B
```

旧 A 不再有效。

## 4. Login

```text
POST /auth/login
```

1. Redis rate limit；
2. MySQL 查询 User/Credential；
3. 校验账号状态；
4. 校验密码；
5. 创建 Refresh Session；
6. issue Access JWT；
7. Audit。

失败错误避免泄露账号存在性。

## 5. 注册模式

Demo：

```text
ALLOW_PUBLIC_REGISTRATION=true
```

Enterprise：

```text
ALLOW_PUBLIC_REGISTRATION=false
Admin invite
→ accept
→ set credential
→ membership
```

## 6. Logout / Disable / Password Reset

Logout：

```text
revoke current refresh session
```

Disable/reset：

```text
MySQL state/credential update
→ revoke sessions
→ bump security_version
```

## 7. Access Token 快速失效

JWT 天然不能服务端即时删除。

V1 组合：

```text
short TTL
+
security_version
+
critical mutation checks
```

对所有请求都查 Redis，还是只对敏感操作/缓存刷新检查，留到 benchmark 后定。

## 8. 授权

```text
Authentication
+
Permission
+
Data Scope
```

Role：

```text
EMPLOYEE
PROJECT_OWNER
FINANCE_REVIEWER
FINANCE_ADMIN
SYSTEM_ADMIN
```

Data Scope：

```text
SELF
PROJECT
TEAM
COST_CENTER
ALL_FINANCE
```

## 9. Permission Cache

Redis：

```text
iam:user-context:{userId}
```

短 TTL。

Role/member 变化后 eviction/version bump。

MySQL 永远是权限 truth。

## 10. Verification / Reset

Redis：

```text
auth:verify:{purpose}:{target}
auth:reset:{id}
```

要求：

- TTL；
- 高熵；
- 单次/有限次数；
- rate limit；
- 不写日志。

如果 V1 不接真实邮件，使用开发邮件 sink/mock，并在 README 标明。

## 11. Rate Limit

V1：

- login by IP；
- login by account；
- verification send；
- password reset。

先实现简单且可测试的固定/滑动窗口。

V2 Gateway 再研究 Token Bucket / Lua。

## 12. Client Token Transport

推荐 Web 后台：

- Refresh Token 优先 HttpOnly/Secure/SameSite Cookie；
- Access Token 采用内存或与最终反向代理架构匹配的受控方案；
- 不默认把长期 Refresh Token 放 localStorage。

CSRF 策略必须与最终 Cookie/Token transport 一起确定。

## 13. Audit

记录：

```text
LOGIN_SUCCESS
LOGIN_FAILED
LOGOUT
PASSWORD_CHANGED
ACCOUNT_DISABLED
ROLE_CHANGED
MEMBERSHIP_CHANGED
SESSION_REVOKED
```

不记录 secret。

## 14. 测试

必须覆盖：

- wrong password；
- disabled account；
- refresh expiry；
- refresh rotation/replay；
- concurrent refresh；
- logout revoke；
- password reset revoke；
- permission cache eviction；
- project data scope；
- Redis unavailable。

## 15. 不做的内容

V1 不做：

```text
OAuth Provider
SAML
SCIM
LDAP
MFA platform
Keycloak clone
```
