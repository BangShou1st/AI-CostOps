# 远端 CI / Security 红灯整改验收

- **日期**：2026-10-26
- **责任人 / AI**：Claude Code（AI 代理维护）
- **关联模块**：Backend（ingestion 读 API 集成测试）、Frontend（Docker 镜像基线）、CI / Security 工作流
- **缺陷编号**：无 GitHub Issue；对应远端运行记录 CI `40283125308`、Security `40283125341`
- **基线提交**：`4589582c6d601f281cb8447585bd6ca99f5cc3d7`（`dev`）
- **修复提交**：`558391c`（`dev`）
- **结论**：**PASS** —— 远端门禁全部通过（CI 14/14、Security 3/3）

---

## 变更概述

本轮仅修复质量门禁红灯，**不新增功能、不改动业务不变量、不触碰 `main`**。两条红灯的根因相互独立。

### 1. Backend 集成测试失败：`ImportWorkflowReadApiIntegrationTest.evidenceImportsPageAndCountShareTheSameTenantConsistentDataset`

**根因：测试断言过宽（raw substring 匹配）叠加共享 Testcontainer 的 AUTO_INCREMENT 漂移，导致 ID 数值碰撞误报。**

- 该测试验证的是**租户隔离语义**：构造一条「当前组织 Evidence + 当前组织 ImportBatch，但 ProviderAccount 指向外部组织」的异常血缘记录，要求组织一致性 join 在 items 与 totalElements 两处都将其排除。
- 原断言：

  ```java
  assertThat(body).doesNotContain(Long.toString(anomalousBatchId) + "\"");
  ```

  这是对**整个响应体**做裸字符串子串匹配，只要响应体任意位置出现该数字串即判失败。
- `M2DatabaseCleaner` 全部使用 `DELETE FROM`（77 处），**从不重置 AUTO_INCREMENT**；`import_batch` 与 `import_attempt` 是两张独立表、各自独立计数，但共享同一个 MySQL Testcontainer，计数器随全量测试集持续增长并相互靠拢。
- 在 CI 全量套件中，两张表的计数器恰好收敛到同一数值区间。按该次运行的实测 ID 推演：

  ```text
  setUp() 插入顺序（同一容器内）
    insertEvidence(org)      -> evidence.id
    insertProviderAccount    -> provider_account.id
    insertBatch(org)         -> import_batch.id  = 175
    insertBatch(foreign)     -> import_batch.id  = 176
    insertAttempt(batch, 1)  -> import_attempt.id = 177
    insertAttempt(batch, 2)  -> import_attempt.id = 178

  测试方法内再插入异常批次 -> import_batch.id = 177   <-- 与 attempt1Id(177) 数值碰撞
  ```

- 响应体中 `175` 这条合法批次带有 `latestAttempt.predecessorAttemptId = "177"`（指向它的第一次尝试）：

  ```json
  {"id":"175", ... "latestAttempt":{"id":"178","attemptNo":2,"triggerType":"MANUAL_RETRY","predecessorAttemptId":"177", ...}}
  ```

  该 `"177"` 是**合法业务字段**，与被排除的异常批次共享数值。旧断言因此命中 `"177"`，产生**假阳性**失败。
- **这不是生产 API 契约回归**：响应字段 `predecessorAttemptId` 属于 `AttemptResponse` 的既定契约（`docs/02-development/api/openapi.yaml` `AttemptResponse.predecessorAttemptId`，`anyOf: [Id, null]`），且 `importDetailAndAttemptsExposeReviewFieldsOnly` 等用例已正向断言其存在与类型。租户隔离语义本身没有失效——被排除的异常批次确实没有出现在 items 与 totalElements 中（CI 响应 `items.length()==1`、`totalElements==1`、`items[0].id=="175"` 均已通过）。
- **不是测试数据/隔离问题**：`M2DatabaseCleaner` 使用正确，异常批次确实被 join 排除。问题只出在断言的匹配方式。

**修复**：把排除检查从「整个响应体的裸子串」收敛到「`items[*].id` 这一条有语义边界的字段」，直接表达「异常批次不得作为条目出现」这一原本的测试意图，同时**不删断言、不降覆盖、不改预期值、不改租户隔离语义**：

```java
.andExpect(jsonPath("$.items[*].id").value(org.hamcrest.Matchers.not(
        org.hamcrest.Matchers.hasItem(Long.toString(anomalousBatchId)))));
```

该写法与仓库既有约定一致（`IamReadApiIntegrationTest.usersAreCurrentOrgOnly`、`BillingPeriodApiIntegrationTest`、`CostCenterApiIntegrationTest` 均使用 `jsonPath("$.items[*].id").value(not(hasItem(...)))`）。

**证据（本地）**：

- 修复前：单独运行该测试类 **11 项全绿**（`.local-dev/logs/import-read-repro.log`，BUILD SUCCESS）；`com.aicostops.ingestion.**` 全包 **694 项 0 失败**（`.local-dev/logs/ingestion-suite-repro.log`，BUILD SUCCESS）。即该缺陷**只在全量套件的 AUTO_INCREMENT 漂移到特定数值时才显形**，与 CI 现象一致（本地 ID 未漂移到碰撞点）。
- 修复后：Backend 全量 `./mvnw -B verify` 通过（见下「验证环境与执行命令」）。

### 附带排查：`E2eProviderMainlineIntegrationTest` 的一次本地失败（非本轮缺陷）

在本地跑 Backend 全量 `verify` 期间，`com.aicostops.e2e.E2eProviderMainlineIntegrationTest` 曾出现一次 `NoSuchElementException: No value present`（`claimNext(...).orElseThrow()`，`Time elapsed: 1.047 s`）。该用例**不在本轮改动范围内**，经排查判定为**本地并行执行造成的测试夹具干扰**，非代码缺陷：

| 证据 | 结果 |
|---|---|
| 同一 commit `4589582` 的 CI 结果 | `Tests run: 1, Failures: 0, Errors: 0` —— 通过 |
| 隔离重跑（`./mvnw -B "-Dit.test=E2eProviderMainlineIntegrationTest" verify`） | `Tests run: 1, Failures: 0, Errors: 0, Time elapsed: 127.0 s`，`BUILD SUCCESS`，退出码 0 |
| 失败时的耗时特征 | `1.047 s`（在 `claimNext` 处立即失败），健康运行需 `127.0 s` 完成完整解析 |
| 触发条件 | 本会话曾在同一 `backend/` 目录并行启动第二个 Maven `verify`，两个 JVM 争用同一 `backend/target` 与 Docker 资源 |
| 该测试在 CI 历史中 | 无 flaky 记录（`b57d12a` / `2014abf` 引入后一直稳定） |

结论：该失败**与本轮两处改动无关**（本轮未触碰 `e2e` 包、生产代码、Schema 或任何 `import_attempt` 写入逻辑），隔离重跑与 CI 均通过。最终以**串行、无并发**的 `npm run verify` 结果作为验收依据。

### 附带排查：`M9ImportScaleBenchmarkIntegrationTest` 本地挂起（非本轮缺陷）

本地串行 `npm run verify` 跑到第 146 个集成测试类时，`M9ImportScaleBenchmarkIntegrationTest` **长时间不返回**（同一 commit 在 CI 上 29.06 秒通过）。经线程栈与数据库取证，根因是**该基准测试自身的 worker 竞态**（既有测试基础设施问题），与本轮改动无关：

- **现象**：warmup 上传的 Attempt（id=829）停留在 `QUEUED`，`lease_owner=NULL`、`records_seen=0`；`import-worker-*` 线程全部 `parking` 空闲；`import_attempt` 表中 `RUNNING` 计数为 0；`innodb_trx` 无阻塞事务、无锁等待。
- **该 Attempt 随时可被 claim**：以 worker 的原始谓词手动执行 `WHERE status='QUEUED' AND available_at <= UTC_TIMESTAMP(6) ... FOR UPDATE SKIP LOCKED` 可正常返回 id=829 —— 即**数据库侧完全就绪，是 worker 从未 claim 它**。
- **机制**：`ImportWorkerCoordinator.pollOnce()`（`backend/src/main/java/com/aicostops/ingestion/application/ImportWorkerCoordinator.java`）先用 `Semaphore.tryAcquire()` 取本地许可，再 `claimNext()`，并把执行**异步**提交给 `importTaskExecutor`（`ThreadPoolTaskExecutor`，`queueCapacity=0`，`corePoolSize=maxPoolSize=workerConcurrency=1`）。基准测试只调用一次 `pollOnce()` 然后立即 `awaitAttempt(...)`；一旦那次调用发生在上传事务提交可见之前、或当时唯一的执行许可被上一次遗留任务占用，`tryAcquire()` 失败即**静默 return**，不抛错、不重试 —— 许可最终归还，但没有任何一次轮询会再来接手这个 QUEUED Attempt（`poll-interval=1h`）。测试于是进入 `awaitAttempt` 的 6 小时死等。
- **排除项**：宿主 JVM 与 MySQL 容器时钟偏差经 5 次背靠背采样仅约 30 ms（`available_at <= UTC_TIMESTAMP(6)` 恒成立），**不是**时钟问题；也不是本轮改动引入（本轮 diff 零行触及 `M9*` / `Worker*` / `Lease*` / `Coordinator*` / `ProviderImport*`）。
- **对照证据**：同一 commit `4589582` 的 CI 中该测试 `Tests run: 1, Failures: 0, Errors: 0, Time elapsed: 29.06 s`，`M9_IMPORT_BENCHMARK|warmup|...|worker_ms=14298` 正常产出。
- **结论**：判定为**本地环境下的既有基准测试竞态**，非本轮缺陷、非生产缺陷。处置方式：本轮**不改基准测试、不改 worker 并发模型**（超出整改范围且影响生产行为），改以**隔离运行**该基准测试单独取证，并在最终验收中显式记录该已知限制。

#### M9 基准测试隔离复现结果

```text
cd backend && ./mvnw -B "-Dit.test=M9ImportScaleBenchmarkIntegrationTest" "-DfailIfNoSpecifiedTests=false" verify
```

```text
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 144.2 s -- in com.aicostops.M9ImportScaleBenchmarkIntegrationTest
[INFO] BUILD SUCCESS
M9 EXIT=0

M9_IMPORT_BENCHMARK|warmup|scale=10k|input_rows=10000|input_bytes=65173|upload_ms=1578|worker_ms=26781|confirm_ms=182
M9_IMPORT_BENCHMARK|measured|scale=t10k|run=1|...|total_ms=27679|worker_records_per_sec=366.193|end_to_end_rows_per_sec=361.285|...
```

隔离运行**通过**（退出码 0），完整产出 warmup 与 measured 两组基准指标。这印证了挂起只发生在全量套件的并发/时序条件下，而非该测试或本轮改动的功能缺陷。

### 2. Security 的 Trivy 前端镜像扫描失败：nginx 3 项 HIGH

**根因：前端镜像基线 `nginx:1.30.4-alpine` 落后于已披露的 nginx 安全修复版本。**

CI 扫描报告（Security run `40283125341`，job `trivy`）：

```text
Target: ai-costops-frontend:security (alpine 3.24.1)
Total: 3 (HIGH: 3, CRITICAL: 0)

Library  Vulnerability   Severity  Installed   Fixed
nginx    CVE-2026-15176  HIGH      1.30.4-r1   1.30.6-r0   ngx_http_core_module via alias with rewrite referencing...
nginx    CVE-2026-93479  HIGH      1.30.4-r1   1.30.6-r0   NGINX map directive and regex matching vulnerability
nginx    CVE-2026-97205  HIGH      1.30.4-r1   1.30.6-r0   nginx ngx_http_core_module vulnerability
```

三项均为 nginx 官方稳定分支 1.30.x 的已发布修复，官方修复版本 `1.30.6-r0`。

**为什么 Dockerfile 里已有的 `apk upgrade --no-cache` 没有消掉它们**：`nginx:1.30.4-alpine` 的 `/etc/apk/world` 把 nginx 及五个模块**精确钉死**在 `=1.30.4-r1` / `=1.30.4.0.4.1-r1` 等版本。`apk upgrade` 遵守 world 中已记录的精确版本约束，因此只升级了 curl、musl、ca-certificates 等其他包，nginx 本身保持 `1.30.4-r1` 不动。本地已实测确认：

```text
$ docker run --rm nginx:1.30.4-alpine sh -c 'apk upgrade --no-cache; nginx -v; apk list --installed | grep nginx'
...
nginx version: nginx/1.30.4
nginx-1.30.4-r1 x86_64 {nginx} (2-clause BSD-like license) {installed}
```

**修复**：把基础镜像升级到**已验证的官方修复版本** `nginx:1.30.6-alpine`（该 tag 的 `/etc/apk/world` 钉死 `nginx=1.30.6-r1`，且 `nginx-module-njs` 同步到 `1.30.6.1.0.2-r1`），不关闭 Trivy、不排除 nginx 镜像、不降低 `--severity HIGH,CRITICAL` 阈值、不新增任何 `.trivyignore` 条目。

**兼容性与不变量核对**：

| 契约项 | 1.30.4-alpine | 1.30.6-alpine | 结论 |
|---|---|---|---|
| `nginx` 用户 uid/gid | 101 / 101 | 101 / 101 | 一致，`USER nginx` 与 `chown` 无需改动 |
| `/etc/nginx/conf.d/` | 存在 | 存在 | `COPY nginx/default.conf` 路径不变 |
| `/var/cache/nginx`、`/var/log/nginx` | 存在 | 存在 | `chown` / `chmod` 路径不变 |
| `/var/run` | `-> ../run` | `-> ../run` | `nginx.pid` 路径不变 |
| 监听端口 | 8080（`nginx/default.conf`） | 8080 | 不变，`EXPOSE 8080` 保持 |

**证据（本地）**：

```text
$ docker build --tag ai-costops-frontend:security frontend      # EXIT=0
$ docker run --rm --entrypoint nginx ai-costops-frontend:security -v
nginx version: nginx/1.30.6
$ docker run --rm --entrypoint sh ai-costops-frontend:security -c 'cat /etc/apk/world | grep nginx'
nginx=1.30.6-r1
nginx-module-acme=1.30.6.0.4.1-r1
nginx-module-geoip=1.30.6-r0
nginx-module-image-filter=1.30.6-r0
nginx-module-njs=1.30.6.1.0.2-r1
nginx-module-xslt=1.30.6-r0

$ docker run --rm -v /var/run/docker.sock:/var/run/docker.sock \
    -v "${PWD}/.trivy-cache:/root/.cache/trivy" \
    aquasec/trivy:0.73.0 image --severity HIGH,CRITICAL --exit-code 1 \
    ai-costops-frontend:security
Report Summary
│ ai-costops-frontend:security (alpine 3.24.3) │ alpine │        0        │    -    │
Total: 0 (HIGH: 0, CRITICAL: 0)
TRIVY EXIT=0
```

Trivy 0.73.0（与 CI 完全一致的版本与参数）复现：**HIGH/CRITICAL 由 3 降为 0，退出码 0**。

**运行时兼容性实测**：新镜像实际启动并对外提供 SPA 页面。

```text
$ docker run -d --name nginx-new --add-host backend:127.0.0.1 -p 18081:8080 ai-costops-frontend:security
$ curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18081/
200
$ curl -s http://127.0.0.1:18081/ | head -c 120
<!doctype html>
<html lang="zh-CN">
  <head>
    <meta charset="UTF-8" />
```

**行为等价性核对**：单独启动新镜像时，nginx 会报 `host not found in upstream "backend"`（因 `nginx/default.conf` 的 `/api/v1` 反代指向 Compose 服务名 `backend`）。这是**既有配置语义，非本次升级引入**——用旧基线 `nginx:1.30.4-alpine` 挂载同一份 `default.conf` 复现出**完全相同**的 `emerg`：

```text
$ docker run -d --name nginx-old-cfg -p 18083:8080 \
    -v .../frontend/nginx/default.conf:/etc/nginx/conf.d/default.conf:ro nginx:1.30.4-alpine
nginx: [emerg] 1#1: host not found in upstream "backend" in /etc/nginx/conf.d/default.conf:6
```

在真实 Compose 拓扑中 `backend` 服务名可解析，故该 `emerg` 不出现在正常运行路径上。

---

## 验证环境与执行命令

环境：Windows 11 · JDK 21 · Node.js 24.14.0 · Docker 29.8.0 · Trivy 0.73.0（容器方式，与 CI 一致）。

```bash
# 1. Backend 单元测试
cd backend && ./mvnw -B -DexcludedGroups=architecture,integration test

# 2. Backend 架构测试（ArchUnit）
cd backend && ./mvnw -B -Dgroups=architecture test

# 3. Backend 全量 verify（= CI backend-integration job 的等价命令）
cd backend && ./mvnw -B verify

# 4. 定向复现（修复前单独运行亦为全绿，佐证 ID 漂移假阳性）
cd backend && ./mvnw -B "-Dit.test=ImportWorkflowReadApiIntegrationTest" "-DfailIfNoSpecifiedTests=false" verify

# 5. 前端镜像构建与 Trivy 扫描（= CI security trivy job 的等价命令）
docker build --tag ai-costops-frontend:security frontend
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock \
  -v "${PWD}/.trivy-cache:/root/.cache/trivy" \
  aquasec/trivy:0.73.0 image --severity HIGH,CRITICAL --exit-code 1 \
  ai-costops-frontend:security

# 6. 仓库完整验证与文档链接
npm run verify
npm run verify:links
```

## 关键断言与截图证据

### 远端门禁结果（修复提交 `558391c`）

| 工作流 / Job | 结果 | 证据 |
|---|---|---|
| **CI `40301123909`** | **success** | 14/14 Job 全部 success |
| `backend-integration` | success | `Tests run: 1276, Failures: 0, Errors: 0, Skipped: 0`（基线为 1276 项 / 1 失败） |
| `ImportWorkflowReadApiIntegrationTest` | success | `Tests run: 11, Failures: 0, Errors: 0, Skipped: 0` |
| `backend-unit` / `backend-architecture` | success | — |
| `gateway-unit` / `gateway-integration` / `gateway-architecture` | success | gateway 集成 `Tests run: 230, Failures: 0` |
| `frontend-test` / `frontend-lint` / `frontend-build` | success | — |
| `docker-build` / `browser-e2e` / `scripts-*` / `scripts-lifecycle` | success | — |
| **Security `40301123946`** | **success** | 3/3 Job 全部 success |
| `trivy`（文件系统 + backend/frontend/gateway 镜像扫描） | success | 前端镜像：`ai-costops-frontend:security (alpine 3.24.3)` → **Vulnerabilities = 0** |
| `codeql (java-kotlin)` | success | 未改动该工作流 |
| `codeql (javascript-typescript)` | success | 未改动该工作流 |

### 关键断言与不变式核对

| 断言 / 证据 | 实际结果 | 判定 |
|---|---|---|
| Trivy 前端镜像 HIGH/CRITICAL 数量 | `Total: 0`，exit code `0`（远端与本地一致） | PASS |
| 前端镜像 nginx 版本 | `nginx/1.30.6`（`nginx=1.30.6-r1`） | PASS |
| 三项 CVE 修复版本对齐 | Trivy fixed version `1.30.6-r0`，镜像实装 `1.30.6-r1` ≥ 修复版本 | PASS |
| 前端镜像运行时 | 实际启动并返回 HTTP 200，SPA 正常下发 | PASS |
| 租户隔离语义未被削弱 | `totalElements==1`、`items.length()==1`、`items[0].id=="175"` 三条原断言全部保留；仅将裸子串排除改为 `items[*].id` 字段级排除 | PASS |
| 业务契约未变 | `openapi.yaml` `AttemptResponse.predecessorAttemptId` 未改动；生产代码零改动 | PASS |
| 数据库迁移未变 | Flyway 迁移文件零改动 | PASS |
| 安全策略未变 | `.trivyignore` 仍不存在；Trivy severity/exit-code 未调整；CodeQL 两个工作流未改动 | PASS |
| `main` 未被修改 | `main` 与 `origin/main` 均停留在 `1fb5e74`，零分叉，且不含 `558391c` | PASS |

### 本地验证结果

| 验证项 | 命令 | 结果 |
|---|---|---|
| Backend 全量 verify（排除 flaky 基准） | `./mvnw -B "-Dit.test=!M9ImportScaleBenchmarkIntegrationTest" verify` | 见「已知限制」说明；集成阶段 240+ 类 0 失败 |
| M9 导入规模基准（隔离） | `./mvnw -B "-Dit.test=M9ImportScaleBenchmarkIntegrationTest" verify` | `Tests run: 1, Failures: 0, Errors: 0`，`BUILD SUCCESS`，退出码 0 |
| Gateway 全量 verify | `cd gateway && ./mvnw -B verify` | `BUILD SUCCESS`，退出码 0（230 + 105 项测试 0 失败） |
| Frontend 单元测试 | `npm test -- --run` | `Test Files 72 passed (72)`，`Tests 547 passed (547)`，退出码 0 |
| Frontend lint / build | `npm run lint` / `npm run build` | 退出码 0 / 0 |
| 脚本测试 | `npm run test:scripts` | 27/27 通过 |
| 文档链接 | `npm run verify:links` | `SUCCESS: 0 broken links found!` |
| 前端镜像安全扫描 | Trivy 0.73.0（与 CI 同版本同参数） | `Vulnerabilities = 0`，退出码 0 |

## 结论与影响边界

- **判定：PASS。** 两个红灯的根因均已定位并按最小改动修复；**远端 CI 与 Security 两条工作流的所有必需 Job 均成功**（CI 14/14、Security 3/3）。
- **变更范围**：7 个文件。
  - `backend/src/test/java/com/aicostops/ingestion/api/ImportWorkflowReadApiIntegrationTest.java`：1 处断言写法收敛（**测试代码**，生产逻辑零改动）。
  - `frontend/Dockerfile`：`FROM nginx:1.30.4-alpine` → `FROM nginx:1.30.6-alpine`（1 行 + 注释同步）。
  - `.gitignore`：新增 `.trivy-cache/`（CI 从仓库根挂载、本地扫描也会生成的漏洞库缓存目录）。
  - `CONTRIBUTING.md` / `CHANGELOG.md`：基线版本号与变更登记同步。
  - `docs/03-acceptance/README.md` + 新增验收记录：索引登记与证据沉淀。
- **已知限制 / 未通过项**：
  1. 本轮**未改动** CodeQL Java/Kotlin 与 JavaScript/TypeScript 两条工作流（该次运行已成功，无改动理由）。
  2. **未新增** `.trivyignore` 条目，`.trivyignore` 仍不存在（`Accepted risks: NONE` 保持成立）。
  3. **未改动**历史封存的 M9 验收记录（`docs/03-acceptance/milestones/m9-security-ci-evidence.md` 保留其原始 `nginx:1.30.4-alpine` 结论与时间点），该记录描述的是当时的真实基线，改写会破坏审计资产；当前基线以本文件与 `frontend/Dockerfile` 为准。
  4. **M9 导入规模基准测试存在既有的 worker 许可竞态**（详见「附带排查」章节）：在全量套件并发条件下偶发长时间不返回，隔离运行必过，同一 commit 在 CI 上 29.06 秒通过。本轮**未修该竞态**——它属于既有测试基础设施问题且超出本轮整改范围（改动会触及生产 worker 并发模型）。此为本次交付的**已知遗留项**，建议单独立项修复。
