# AI CostOps 贡献与协作规范

## 1. 开发流程

```text
GitHub Issue
→ Branch
→ Code + Tests
→ Pull Request
→ CI
→ Optional Review / Discussion
→ Squash Merge
```

禁止直接在 `main` 开发。

## 2. 开始一个 Issue

```bash
git switch main
git pull --ff-only origin main
git switch -c <branch-name>
```

Branch：

```text
feat/*
fix/*
refactor/*
test/*
perf/*
docs/*
chore/*
```

## 3. Commit

使用 Conventional Commit 风格：

```text
feat(iam): implement password login
feat(provider): add MiMo adapter
fix(import): recover expired worker lease
test(budget): cover concurrent activation
docs(api): update budget contract
chore(ci): add backend workflow
```

避免：

```text
update
final
fix
111
改一下
```

## 4. Pull Request

PR 必须说明：

```text
What
Why
Design / Key Decisions
Testing
Database / Migration Impact
Risks / Failure Paths
Related Issue / Design
```

数据库变化必须包含 Flyway。

## 5. API Change

任何 API 变化必须同步：

```text
docs/02-development/api/openapi.yaml
Controller / DTO
Frontend Type / Client
Contract / Integration Test
```

禁止口头修改接口。

## 6. Review

Peer Review 不是全局 Merge Gate。重要模块、高风险变更或作者主动请求时，建议由另一名 Contributor Review。

如进行 Review，优先检查相关项：

```text
业务不变量
Transaction
Idempotency
Concurrency
SQL / Index
Permission / Scope
Redis Truth Boundary
Migration
Failure Path
Test
```

普通讨论使用 Comment 即可；是否需要修改由 PR Author 结合设计、测试和 CI 结果处理。

## 7. Secret / Provider Data

禁止提交：

```text
.env
真实密码
JWT Signing Key
Provider API Key
真实 Provider 原始导出文件
本地数据库数据
日志
Build Output
```

Provider Test Fixture 只允许：

```text
REAL_SCHEMA_SANITIZED
OFFICIAL_SCHEMA_SYNTHETIC
SYNTHETIC_ENTERPRISE
```

## 8. 集成测试隔离（必读）

所有 Backend 集成测试跑在**同一个** MySQL Testcontainer 里
（`MySqlContainerSupport.MYSQL` 是 static 字段）。因此：

> **测试类之间会互相看到对方留下的数据。** 清理不完整会直接变成下一个测试类的
> 外键约束失败，且失败与否取决于 Failsafe 的执行顺序——这类问题会以"随机红"的形式出现。

### 清理必须用 `M2DatabaseCleaner`

```java
com.aicostops.testsupport.M2DatabaseCleaner.clean(jdbc);
```

它是一趟 FK-safe 的全量清理（child-before-parent），仓库里 170+ 个测试类都在用。
**不要**自己手写局部的 `DELETE FROM app_user`——手写列表几乎必然漏掉外键子表。

`app_user` 的外键子表（漏掉任何一个都会报 `DataIntegrityViolationException`）：

```text
user_credential        (fk_user_credential_user)
organization_member    (fk_organization_member_user)
invitation             (fk_invitation_accepted_user)
audit_event            (fk_audit_event_actor)
cost_history_coverage  (fk_cost_coverage_actor, V28 新增)
```

需要保留 seed 的 `role` / `permission` / `role_permission` 目录时，只额外删自己创建的 role：

```java
M2DatabaseCleaner.clean(jdbc);
jdbc.update("DELETE rp FROM role_permission rp JOIN `role` r ON r.id=rp.role_id WHERE r.code=?", code);
jdbc.update("DELETE FROM `role` WHERE code=?", code);
```

### 自己建的数据必须自己清

`@BeforeEach` 建了数据就要在 `@AfterEach` 清掉。只在 `@BeforeEach` 清理是不够的：
类的**最后一个**测试留下的数据会泄漏给下一个类。

```java
@BeforeEach
void setUp() {
    cleanDatabase();
    /* 造夹具 */
}

@AfterEach
void tearDown() {
    cleanDatabase();     // 必须有
}
```

### 禁止的"修复"方式

```text
删断言            → 掩盖问题
加 @Disabled      → 掩盖问题
删掉失败的那个测试 → 掩盖问题
```

CI 红了就查根因。共享容器下的失败几乎总是**隔离问题**，不是业务逻辑问题。

## 9. AI-assisted Coding

AI 生成代码的责任仍属于 PR Author。

合并前必须：

```text
理解代码
运行测试
能解释设计
必要时接受 Peer Review
```

## 10. 更详细规范

开发：

```text
docs/02-development/
```

Review / 验收：

```text
docs/03-acceptance/
```

AI 编码代理的项目上下文：

```text
AGENTS.md
```

## 11. 持续安全 CI（M9 / AIC-079）

每次 PR 与 main push 都会运行 `Security` workflow，必须通过：

```text
codeql (java-kotlin)
codeql (javascript-typescript)
trivy (filesystem: vuln, misconfig, secret — offline, DB cached)
trivy (backend image: vuln)
trivy (frontend image: vuln)
```

- CodeQL Action v4，语言为 `java-kotlin` 与 `javascript-typescript`，使用仓库真实 Java / TypeScript build。
- Trivy 固定 `0.73.0`（docker 镜像方式运行，与本地复现完全一致）。
- Filesystem 扫描覆盖 `vuln + misconfig + secret`（`HIGH,CRITICAL` blocking），对 `backend/pom.xml` 与 `frontend/package-lock.json` 的依赖清单基于本地检出的 manifest 与缓存的漏洞 DB（`--offline-scan` + `actions/cache` 的 `.trivy-cache` 挂载），不 blanket 跳过 `backend/pom.xml`，也不依赖 runner 临时访问 Maven Central；镜像扫描额外验证实际构建出的依赖集合。
- 策略：`HIGH,CRITICAL` 默认 blocking；secret finding 默认 blocking；禁止 blanket ignore。
- `.trivyignore` 只在存在真实、已评审、限期的 HIGH/CRITICAL 且当前无法修复时才逐项记录（exact finding、reason、owner/context、expiry/review date）；当前无 accepted risk（`Accepted risks: NONE`），`.trivyignore` 已随 `nginx:1.30.6-alpine` 升级而删除 — 升级前 5 条 nginx 1.28.3-r1 限期 accepted risk、nginx 1.28.2 的 10 条 CVE，以及 1.30.4-r1 上新披露的 CVE-2026-15176 / CVE-2026-93479 / CVE-2026-97205 三项 HIGH，均已在 1.30.6 基线上消失。
- 只有 `codeql` job 拥有 `security-events: write`；workflow 默认 `contents: read`。

本地复现（PowerShell）：

```powershell
Set-Location "E:\AI-CostOps"

# Build images first (needed by the image scans)
docker build --tag ai-costops-backend:local backend
docker build --tag ai-costops-frontend:local frontend
docker run --rm ai-costops-frontend:local id   # must be non-root (uid 101 nginx)

# 1. filesystem scan — CI-identical (vuln + misconfig + secret, offline, DB cached)
docker run --rm `
  -v "${PWD}:/workspace" `
  -v "${PWD}/.trivy-cache:/root/.cache/trivy" `
  aquasec/trivy:0.73.0 `
  fs --scanners vuln,misconfig,secret `
  --severity HIGH,CRITICAL `
  --exit-code 1 --offline-scan `
  --skip-dirs /workspace/.git,/workspace/frontend/node_modules,/workspace/frontend/dist,/workspace/frontend/playwright-report,/workspace/frontend/test-results,/workspace/backend/target,/workspace/.trivy-cache `
  /workspace

# 2. backend / frontend 镜像扫描（no ignorefile; any HIGH/CRITICAL blocks）
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "${PWD}/.trivy-cache:/root/.cache/trivy" `
  aquasec/trivy:0.73.0 image --severity HIGH,CRITICAL --exit-code 1 ai-costops-backend:local
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "${PWD}/.trivy-cache:/root/.cache/trivy" `
  aquasec/trivy:0.73.0 image --severity HIGH,CRITICAL --exit-code 1 ai-costops-frontend:local
```

首次出现 findings 时逐项处理：`upgrade/fix`、`prove false positive`、或 `explicit accepted risk`（必须写入 evidence 与 `.trivyignore` 的对应条目）。
