# 2026-10-15 Gateway 间歇性 403 调查与定向修复

基线：`codex/history-coverage-20261012@3244dd3558a0221d3e8b4584e74f25bdb14b7a33`（本地 = 远端）。
本批**零生产代码改动**：只改 gateway 测试支撑（`GatewayTestFixture`）、一个测试断言诊断与新增一个防回归测试。
未跟踪的 `.freebuff/` 原样保留；无 reset/clean/强制推送；未建 PR、未合并、未部署、未调用付费 Provider。
上批 [`2026-10-14-authorization-change-and-recovery-review.md`](2026-10-14-authorization-change-and-recovery-review.md)
§6.2 记录的 `GatewayHeaderLimitIntegrationTest.normalHeadersReachBusinessPath` 403 属于本批调查对象。

## 1. 本批结论

- **403 已定位到层与错误码**：`HTTP 403` = `GATEWAY_FORBIDDEN`（`permission_error`），业务消息
  **`Project is not active`**，由 **认证层** `GatewayAuthenticationManager.validateProjectActive` 产出。
  不是路由候选资格拒绝（消息形态不符），也不是限流/配额/熔断（HTTP 状态不符）。证据见 §2。
- **根因（测试支撑缺陷，非 Redis）：** `GatewayTestFixture` 用 `SELECT LAST_INSERT_ID()` 通过
  **连接池** 派生所有 AUTO_INCREMENT id。`LAST_INSERT_ID()` 是**连接作用域**的，而
  `JdbcTemplate` 每条语句各自 borrow/release 连接；当连接池把一个刚归还的连接直接交给等待者
  （HikariCP handoff）时，随后的 `SELECT LAST_INSERT_ID()` 会由**另一条连接**作答。
  `gateway_credential.project_id` / `financial_scope_id` 是**仅有的两个无外键的派生引用**，错误 id
  因此被静默写入，认证层再读该凭证时 `project` 谓词失败 → 间歇性 403。证据见 §3、§4。
- **`Redis 残留` 假设不成立**：gateway 使用的全部 Redis key 都以**每次 seed 都新建的自增 id**
  为作用域（见 §4.3），跨测试类残留无法改变任一请求的鉴权/资格判定。本批**未使用**
  `FLUSHALL` / `FLUSHDB` 或任何全局清理。
- **修复**：`seed` / `addMimoCompatibleCandidate` 固定到**单一物理连接**（`SingleConnectionDataSource`），
  使 id 派生与对应 INSERT 必然同连接。见 §5。

## 2. 403 分层定位

### 2.1 复现到的完整交换（决定性证据）

随机类顺序的全量 gateway `verify` 复现了同类间歇 403（`expected 404` → `was 403`），
测试框架打印了完整请求/响应：

```
> GET http://localhost:59482/v1/gateway/requests/gwr_00000000-0000-4000-8000-000000000000
> Authorization: [Bearer aic_0123456789ab_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA]
< 403 FORBIDDEN Forbidden
< X-Trace-Id: [trc_85c1626a-347f-4496-b8ea-be1293a5879d]
< Content-Type: [application/json]
< content-length: [111]
```

失败：`GatewayRequestStatusControllerTest.nonexistentRequestIsPrivacyPreserving404:143
Status expected:<404 NOT_FOUND> but was:<403 FORBIDDEN>`。

### 2.2 `content-length: 111` 唯一对应哪条消息

冻结错误信封为 `{"error":{"message":..,"type":..,"param":null,"code":..}}`。
对全部可达的 `GATEWAY_FORBIDDEN`（`permission_error`）消息计算**精确字节长度**：

| 业务消息 | 字节长度 |
| --- | --- |
| Principal is not active | 113 |
| **Project is not active** | **111** |
| Financial scope is not active | 119 |
| The requested model is not allowed for this credential | 144 |
| The requested model is not active | 123 |
| No eligible Provider route is available for the requested model | 153 |
| No eligible Provider route is available | 129 |

111 只匹配 **`Project is not active`**。该字符串在全仓库**只有一处**产生：
`GatewayAuthenticationManager.validateProjectActive`（`gateway/.../auth/GatewayAuthenticationManager.java:121`）。

### 2.3 该分支的成立条件

`validateProjectActive` 在 `readMapper.findProjectStatus(row.projectId(), row.orgId())`
（`SELECT status FROM project WHERE id=? AND org_id=?`）**非 ACTIVE** 时抛 403。即：
凭证行的 `project_id` 在其 `org_id` 下无法解析到 ACTIVE 的 `project` 行。

### 2.4 逐层排除

| 候选层 | 结论 | 依据 |
| --- | --- | --- |
| 认证层 | **命中** | 111 字节唯一对应 `Project is not active`，唯一来源为认证层 |
| 请求路由资格 | 排除 | 该类失败消息为 `No eligible Provider route ...`（153/129 字节），与 111 不符 |
| 限流 / 配额 | 排除 | 对应 `GATEWAY_RATE_LIMITED` = HTTP 429，而观测为 403 |
| 熔断（Redis） | 排除（对该请求） | 熔断打开进入“无合格路由”分支 → 153/129 字节消息，非 111 |
| 预算 | 排除 | `GATEWAY_BUDGET_EXHAUSTED` = HTTP 429 |
| Spring Security 链 | 排除 | `GatewaySecurityConfiguration` 为 `permitAll`，不产出 403 |

## 3. 复现记录（3 次独立复现，均为真实 MySQL 8.4 + Redis 容器）

| # | 配置 | 现象 | 日志 |
| --- | --- | --- | --- |
| 1 | 全量 gateway `verify`，`-Dfailsafe.runOrder=random -Dsurefire.runOrder=random` | `GatewayRequestStatusControllerTest.nonexistentRequestIsPrivacyPreserving404` 403（§2.1 完整响应体） | `.local-dev/reviews/2026-10-15-gateway-403/full-gateway-verify-2-random.log` |
| 2 | 随机类顺序 surefire（带一致性探针） | `GatewayRequestStatusControllerTest.seed:78` 抛 `DataIntegrityViolationException`：`INSERT INTO billing_period ... fk_billing_period_org FOREIGN KEY (org_id) REFERENCES organization(id)` 失败 | `.local-dev/reviews/2026-10-15-gateway-403/probe-loop-B1.log` |
| 3 | 新增防回归测试（临时关闭本次修复） | 2/2 失败：`gateway_credential` / `pricing_rate` 外键失败 | `.local-dev/reviews/2026-10-15-gateway-403/guard-without-fix-v2-1.log`、`-2.log` |

**未复现的配置（如实记录）**：`GatewayHeaderLimitIntegrationTest` 单独运行 3/3 通过（§7.1）；
默认顺序全量 `verify` 174+84 全绿（§7.2）。即：**上批 §6.2 报出的那一次 `normalHeadersReachBusinessPath`
403 本身在本批未能再次复现**；本批复现的是**同一根因、同一错误码、同一层的另一个测试**，
且复现 #2 直接证明 id 派生可跨连接读回（该路径注入的是有外键的 `org_id`，因而表现为错误而非 403）。

## 4. 根因

### 4.1 机制

`GatewayTestFixture` 每插入一行后用 `SELECT LAST_INSERT_ID()` 取回该行 id：

```java
private static long insertLastId(JdbcTemplate jdbc) {
    return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
}
```

- `LAST_INSERT_ID()` 是**会话/连接作用域**的：只反映**该连接**最近一次生成自增值的语句。
- 夹具用的是应用共享的池化 `JdbcTemplate`：无事务时每条语句
  `DataSourceUtils.getConnection` → 执行 → `releaseConnection`，**不保证 INSERT 与随后的 SELECT
  落在同一物理连接**。测试 pool 为 `maximum-pool-size: 2`、`minimum-idle: 0`
  （`gateway/src/test/resources/application-test-defaults.yml`），且同一上下文内还有
  `@Scheduled` 的 advisor worker 线程会向同一池借用连接；当池把一个刚归还的连接**直接交给等待者**
  时，本线程的 `SELECT LAST_INSERT_ID()` 会拿到**别的连接**的值。
- 复现 #2 正是这一点的直接证据：`organization` 的 id 被从别处读回 → 依赖该 id 的
  `billing_period(org_id)` 触发 `fk_billing_period_org` → 测试报错。

### 4.2 为什么是“间歇性”、且错误为何变成 403

- 派生 id 是否被污染取决于**连接交接的时序窗口**（另一线程恰好在 INSERT 与 SELECT 之间成为等待者），
  因此只在负载/调度交错时出现 —— 与“单类隔离重跑不复现、全量随机顺序偶发”一致。
- **被污染的 id 落在哪一列决定症状**：
  - 落在**有外键**的列（`org_id`/`period_id`/`service_identity_id`/`provider_account_id`/
    `pricing_version_id`/`pricing_rate.*`）→ 后续 INSERT 触发外键失败 → 测试 **ERROR**（复现 #2、#3）。
  - 落在**无外键**的列（`gateway_credential.project_id`、`financial_scope_id`）→ **静默写入**，
    直到认证层读该凭证时才暴露 → **403 `Project is not active`**（复现 #1；上批 §6.2 的
    `normalHeadersReachBusinessPath` `expected 200 but was 403` 与此同型）。
- 外键事实核对：`gateway_credential` 的 FK 只有 `org_id`、`(organization_member_id,org_id)`、
  `(service_identity_id,org_id)`、`(predecessor_credential_id,org_id)`；
  `project_id` / `financial_scope_id` **无 FK**（`V18__m11_gateway_edge_foundation.sql`，
  且后续迁移未为其新增 FK）。同时 `uq_gateway_credential_prefix UNIQUE (credential_prefix)` 保证
  按前缀只可能解析到唯一一行 —— 因此“读到别的测试的凭证行”这条路径不成立，问题只能在**本行自身字段**。

### 4.3 为什么不是 Redis（对 §6.2 假设的证伪）

Gateway 全部 Redis key 均以**每次 seed 新建的自增 id** 为作用域：

| 用途 | key 形态 | 作用域 |
| --- | --- | --- |
| 限流令牌桶 | `aicostops:v2:gateway:ratelimit:{credentialId}` | 每次 seed 新建的 `gateway_credential.id` |
| 每日配额 | `aicostops:v2:gateway:quota:{credentialId}:{yyyyMMddUTC}` | 同上 |
| 路由熔断 | `aicostops:gateway:circuit:v1:{orgId}:{providerAccountId}:{providerModelId}` | 每次 seed 新建的自增 id |

`GatewayTestFixture.seed` 每次创建**新的 organization/credential/provider_account/provider_model**，
其自增 id 在同一容器内不重复。因此**跨测试类的 Redis 残留不可能改变本请求的鉴权或路由资格判定**；
`403 Project is not active` 完全由数据库行自身的字段值决定。故 §6.2 的“共享 Redis 污染”假设
**不成立**，本批也不采用任何全局清理作为修复。

## 5. 修复（最小，仅测试支撑）

`gateway/src/test/java/com/aicostops/gateway/testsupport/GatewayTestFixture.java`

- 新增 `onOneConnection(sharedJdbc, body)`：向应用 DataSource 借**一条**连接，用
  `SingleConnectionDataSource(connection, true)`（`suppressClose=true`）包一层局部 `JdbcTemplate`，
  在其上执行整个夹具体，结束后关闭真实连接。
- `seed(...)` 与 `addMimoCompatibleCandidate(...)` 改为经 `onOneConnection` 执行；
  原有 SQL 与语句序列**不变**，仅连接作用域被收拢到单一连接，`LAST_INSERT_ID()` 因此必然对应当前 INSERT。
- 未引入新框架、新依赖或新配置；未改动 `clean()` 的清理集合（避免扩大影响面）。

未改动任何生产代码（`gateway/src/main/**` 与 backend 均无改动）。

附带一处**测试诊断**改动（不放宽任何断言）：
`GatewayHeaderLimitIntegrationTest.normalHeadersReachBusinessPath` 的 200 断言加上失败信息
`as("unexpected Gateway status; body=%s", response.body())`，使该用例一旦再次出现非 200，
失败信息即直接给出冻结错误信封（层次与业务码），避免只报裸状态码。

## 6. 防回归测试

`gateway/src/test/java/com/aicostops/gateway/testsupport/GatewaySeedIdDerivationIntegrationTest.java`（2 项）

1. `everySeededReferenceResolvesInsideItsOwnOrganization`：seed 后断言
   `project`/`service_identity`/`billing_period`/`provider_account`/`pricing_version`/
   `routing_policy_candidate` 均能在**本组织**内解析，并逐字段核对凭证行
   `org_id`/`project_id`/`service_identity_id`/`financial_scope_id` 与派生值一致
   —— 即认证层实际校验的那组引用。
2. `idsStayConsistentWhileThePoolIsConcurrentlyBorrowedByOtherThreads`：3 个并发借用线程持续争用
   这两个连接（制造池的 borrow/handoff 窗口），在此期间连续 seed 10 次并断言引用一致。

验证：**关闭修复（临时直连共享 JdbcTemplate）时 2/2 失败**（`gateway_credential`、`pricing_rate` 外键错误）；
**启用修复时 2/2 通过**（§7.3）。因此该测试确为可用的防回归测试，而非仅文档性断言。

## 7. 验证记录

全部为真实 Testcontainers（MySQL 8.4 + Redis 8.8.1-alpine），串行执行。

### 7.1 直接受影响的测试

命令（`gateway/`）：`.\mvnw.cmd -B test-compile failsafe:integration-test failsafe:verify
"-Dit.test=GatewayHeaderLimitIntegrationTest" "-Dfailsafe.failIfNoSpecifiedTests=false"`

| 运行 | 结果 | 退出码 |
| --- | --- | --- |
| 修复前（基线，1 次） | 3/3 通过 | 0 |
| 修复后 | 见 §7.4（含于全量 verify） | — |

### 7.2 组合/顺序复跑

| 配置 | 修复前 | 修复后 |
| --- | --- | --- |
| 默认顺序全量 `.\mvnw.cmd -B verify`（`gateway/`） | 174 + 84 全通过（1 次，退出码 0） | **174 + 86 全通过（1 次，退出码 0）**，见 §7.4 |
| `-Dfailsafe.runOrder=random -Dsurefire.runOrder=random` 全量 `verify` | **1 failure**：`GatewayRequestStatusControllerTest.nonexistentRequestIsPrivacyPreserving404` expected 404 but was 403（§2.1，退出码 1） | **174 + 86 全通过（1 次，退出码 0）**，见 §7.4 |

### 7.3 修复后定向与新增测试

命令（`gateway/`）：`.\mvnw.cmd -B test-compile failsafe:integration-test failsafe:verify
"-Dit.test=GatewaySeedIdDerivationIntegrationTest" "-Dfailsafe.failIfNoSpecifiedTests=false"`

| 运行 | 结果 | 退出码 |
| --- | --- | --- |
| 防回归（修复关闭，2 次） | **2/2 失败**（`gateway_credential` / `pricing_rate` 外键错误，证明测试有效） | 1 |
| 防回归（修复启用，2 次） | **2/2 通过** | 0 |

### 7.4 修复后全量（真实 MySQL 8.4 + Redis）

命令（`gateway/`）：`.\mvnw.cmd -B verify`（默认顺序）；记录目录
`.local-dev/reviews/2026-10-15-gateway-403/postfix-full-gateway-verify-1.log`。

| 阶段 | 结果 |
| --- | --- |
| Gateway surefire | 174 项，0 failures、0 errors |
| Gateway failsafe | **86 项**（84 原有 + 2 新增防回归），0 failures、0 errors |
| 退出码 / 耗时 | **0** / 4:15 —— BUILD SUCCESS |

命令（`gateway/`）：`.\mvnw.cmd -B verify "-Dfailsafe.runOrder=random" "-Dsurefire.runOrder=random"`；
记录目录 `.local-dev/reviews/2026-10-15-gateway-403/postfix-full-gateway-verify-2-random.log`。
**这是修复前复现 403 的同一配置**：修复后 174 + 86 全通过，退出码 0，5:21 —— BUILD SUCCESS。

### 7.5 根目录 `npm run verify`（修复后，全绿，退出码 0）

命令（仓库根）：`npm run verify`。日志：`.local-dev/reviews/2026-10-15-gateway-403/root-npm-verify.log`；
隔离报告目录：`.local-dev/builds/verify-1792080244879-0f954242-7f89-4183-845e-6f11ba771f77`。

| 阶段 | 结果 |
| --- | --- |
| 脚本单测 + 生命周期（含真实 dev/infra CLI 端到端） | 全部通过 |
| 后端 surefire | **654** 项，0 failures、0 errors、1 Windows 平台跳过 |
| 后端 failsafe | **1242** 项，0 failures、0 errors |
| Gateway surefire | 174 项，0 failures、0 errors |
| Gateway failsafe | **86** 项，0 failures、0 errors（含新增防回归 2 项） |
| 前端 test / lint / production build | 全部通过（`✓ built in 1.04s`） |
| **退出码** | **0** |

全程真实 Testcontainers（MySQL 8.4 / Redis / MinIO），未绕过 Docker 检查；未出现上批 §6.2 的 403。

## 8. 限制与遗留

- **上批 §6.2 的具体一次 403 未再次复现**：本批通过“同根因、同层、同错误码”的**另一个测试**复现并定位，
  另有一次**直接证明 id 跨连接读回**的 seed 外键失败。仍属时序相关缺陷：即使修复后，也无法用“永不复现”
  来证明修复；本批以**机制闭合 + 防回归测试在关闭修复时稳定失败**作为证据。
- **测试执行顺序本身不稳定**（Maven `runOrder` 默认为文件系统序；随机序会变动），但 §7.2 显示默认顺序
  下也可出现同类 403，故不是“某一种顺序专属”。
- 未纳入本批（避免扩大范围）：`GatewayTestFixture.clean()` 仍是**全局无 WHERE** 清理，且 `seed` 使用
  **固定 `credential_prefix`**（`aic_0123456789ab`）。两者在**串行**执行下不构成已证实缺陷
  （`uq_gateway_credential_prefix` 保证唯一），但会在**并行**执行测试时互相破坏；本批不改，列入待验证风险。
- `Redis 残留` 假设已证伪（§4.3），但**未**为 gateway 测试增加 Redis 命名空间/flush 之类的隔离机制。
- 未执行远程 CI。

## 9. 提交

- 修复前 HEAD：`3244dd3558a0221d3e8b4584e74f25bdb14b7a33`
- **修复提交**：`4f2d389a3898641168e44c2e0cb14247233962d1`
  （`fix(gateway): pin test fixture seed to one connection for id derivation`）
- 本验收文档提交：见仓库 `git log`（`docs(acceptance): record gateway intermittent 403 root cause and fixture fix`）
- 分支：`codex/history-coverage-20261012`，**非强制**推送；未建 PR、未合并 main、未部署。
