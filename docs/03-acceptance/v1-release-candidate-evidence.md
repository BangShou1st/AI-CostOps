# AIC-072 V1 Release Candidate Evidence

- 日期：2026-08-23
- 分支：release/m8-v1-rc
- 起始提交：a076b43f159bbe4bd0d481287d104783e5051a1b
- RC 范围：AIC-071 + AIC-072
- 发布级别：Release Candidate；不是生产发布

## 1. Scope and gate boundary

本 RC 收口的是已有 V1 主链路、M8 质量证据和可重复的完整 Compose smoke。它不新增 AIC-073，不创建 tag / Release，不执行 merge，也不把 synthetic Provider credential 当作真实生产验证。

主链路：

~~~text
Provider Statement / Expense Evidence
→ Evidence & Ingestion
→ Canonical Cost Facts
→ Attribution
→ Budget / Commitment
→ Approval
→ Ledger
→ Reconciliation
→ Billing Period Close
~~~

## 2. Milestone chain

| Milestone | Evidence / implementation boundary |
|---|---|
| M0 | Foundation acceptance、Docker、CI、MySQL integration baseline；见 docs/03-acceptance/implementation/08-m0-foundation-evidence.md |
| M1–M6 | 仓库现有 acceptance matrix、详细设计、API contract tests 与实现共同构成已合入的 V1 主链路证据；见 docs/03-acceptance/02-Milestone验收证据矩阵.md 与 docs/03-acceptance/implementation/06-v1-release-acceptance.md |
| M7 | Provider / expense 深链路 E2E、权限与 audit sensitive-action matrix；见 docs/02-development/implementation/05-bootstrap-local-development-runbook.md、backend/src/test/java/com/aicostops/integration/M7E2EProviderIT.java、backend/src/test/java/com/aicostops/integration/M7E2EExpenseIT.java 和 docs/superpowers/specs/2026-08-23-m7-audit-sensitive-action-matrix.md |
| M8 Stage 2 | AIC-066 schema/query、AIC-067 import benchmark、AIC-068 financial concurrency、AIC-069 runtime failure injection、AIC-070 security review、AIC-071 Compose smoke；详见下一节 |

M7 的 E2E 继续覆盖 DeepSeek synthetic import → worker → confirm → allocation → posting → reconciliation → close，以及 employee expense → evidence → submit → finance approve → allocation → posting → reconciliation → close。AIC-071 的 Compose smoke 在 canonical confirmation、expense submission 和 audit query 后停止，边界互补而非重复。

## 3. M8 hardening evidence

| Issue | Evidence | Result |
|---|---|---|
| AIC-066 | docs/superpowers/specs/2026-08-23-m8-schema-query-index-review.md | V17 budget lookup composite index、EXPLAIN 与 query/index review 已记录 |
| AIC-067 | docs/superpowers/specs/2026-08-23-m8-import-scale-benchmark.md | synthetic DeepSeek 64 / 256 / 1024 row benchmark 已记录 |
| AIC-068 | docs/superpowers/specs/2026-08-23-m8-financial-concurrency-failure.md | commitment invariant、duplicate ledger、rollback、close/post fence、deadlock retry 已记录 |
| AIC-069 | docs/superpowers/specs/2026-08-23-m8-runtime-failure-injection.md | Redis / MinIO fail-closed、Workbench fallback、worker lease recovery / fencing 已记录 |
| AIC-070 | docs/superpowers/specs/2026-08-23-m8-security-review.md | scoped review 无 P0/P1；未确认真实 secret；未声称 100% secure |
| AIC-071 | docs/superpowers/specs/2026-08-23-m8-compose-smoke.md | fresh volume、五服务健康、真实登录、Workbench、DeepSeek import、expense submit、audit、restart persistence 全部 PASS |

## 4. Automated regression matrix

| 检查 | 命令 / 范围 | 结果 |
|---|---|---|
| Backend unit / component | mvnw -B -DexcludedGroups=architecture,integration test | PASS；437 tests，Failures 0，Errors 0，Skipped 1 |
| Backend architecture | mvnw -B -Dgroups=architecture test | PASS；34 tests，Failures 0，Errors 0，Skipped 0 |
| Backend integration | mvnw -B -Dgroups=integration verify | PASS；800 tests，Failures 0，Errors 0，Skipped 0 |
| Direct OpenAPI contract assertions | M1 / M5 / M6 / M7 contract tests | PASS；23 assertions，Failures 0，Errors 0，Skipped 0 |
| Frontend lint | npm run lint | PASS |
| Frontend Vitest | npm test -- --run | PASS；47 files，432 tests |
| Frontend build | npm run build | PASS；Vite 8.2.1 |
| Docker images | docker compose --env-file .env.example build | PASS；backend、frontend |
| Compose application smoke | scripts/smoke-v1.ps1 | PASS；SMOKE_V1_PASS |
| Compose restart persistence | down（不带 -v）→ up → app/db reads | PASS |

Frontend test output includes known jsdom advisory messages about getComputedStyle / navigation; no test failed。Vite reports a large generated JS chunk（约 1,556.18 kB）；这是现有 bundle-size follow-up，不是本 RC 的 correctness failure。

## 4.5 Human UAT Results

- 日期：2026-08-24
- 结果：PASS

### 覆盖范围

| 检查项 | 结果 |
|--------|------|
| Functional scenarios | 32/32 PASS |
| Critical state branches | 14/14 PASS |
| P0/P1 defects | 0 |

### Import

- FAILED Import：FAILED → CANCELED — **PASS**

### Close Flow

- OPEN_IMPORT blockers：7/7 resolved — **PASS**
- Period Close：**PASS**
- Period status：CLOSED

### Closed Period Guard

- Request：`POST /expenses/{id}/approve`
- Result：`409 PERIOD_NOT_OPEN`
- Expected behavior：**PASS**

### Reopen

- Closed period reopen：**PASS**

### Limitations

- SMTP：Local environment only. No production mail delivery tested.

## 5. AIC-071 Compose evidence summary

最终 full Compose 使用 Windows Docker Desktop；默认 8080 被本机另一个 Java 服务占用，因此用一次性 18080 host override 完成复验。五个服务、MySQL / Redis / MinIO readiness、Flyway V1→V17、backend liveness、frontend HTTP、dev bootstrap login、Workbench、合成 DeepSeek import / confirm、canonical charge、expense evidence / submit、audit query 均 PASS。

首次 fresh run 暴露当前月份缺少 OPEN billing period 的 409；已通过 dev-only、幂等、同事务 bootstrap 修复，且未绕过账期校验。修复后 fresh volume rerun 和不删 volume restart persistence 均 PASS。密码、JWT 和 synthetic Provider key 未打印。

## 6. Import benchmark numbers

AIC-067 使用 synthetic DeepSeek export；每个规模 5 次 warm run，以下为实际观测值：

| Rows | Worker median / range | Worker throughput | Total median / range | E2E throughput |
|---:|---:|---:|---:|---:|
| 64 | 427 ms / 325–427 ms | 149.883 records/s | 572 ms / 437–584 ms | 111.888 rows/s |
| 256 | 1,114 ms / 1,065–1,204 ms | 229.803 records/s | 1,290 ms / 1,165–1,360 ms | 198.450 rows/s |
| 1,024 | 4,602 ms / 3,802–5,183 ms | 222.512 records/s | 4,749 ms / 3,956–5,373 ms | 215.624 rows/s |

限制：没有 JVM heap / GC / CPU profile、statement-level query count、并发 import worker throughput、超过 1,024 rows、真实 Provider export、cloud object-storage latency 或 replication 测量。数字不应外推为生产容量承诺。

## 7. Financial and runtime invariants

AIC-068 实际结果：100 个并发 commitment 请求中 10 个 ACTIVE、90 个 REQUESTED；committed amount 10.00000000；budget version 10。重复请求只产生一个 posting / ledger entry / audit；rollback 无 partial write；close/post fence 生效；deadlock retry 恰好 3 次。

AIC-069 实际结果：Redis auth / rate-limit failure fail-closed 为 503；Workbench Redis failure 走 MySQL fallback；MinIO failure fail-closed；worker lease recovery 与 fencing 生效。

AIC-070 是有边界的 scoped security review：未发现 P0/P1，未确认真实 secret；这不是“100% secure”证明。

## 8. Provider support boundary

已实现 adapter / canonical mapping 的 Provider 为 DeepSeek、MiMo、Kimi、GLM、OpenAI。M8 Compose smoke 与 benchmark 使用 synthetic DeepSeek；其他 Provider 的 fixture / adapter tests 不能解释为真实生产导入认证。真实账户、真实 API key 和真实企业账单不在本 RC 环境。

## 9. Known limitations and non-blocking follow-ups

- AIC-067 的规模、并发、真实 export、对象存储延迟与 replication 限制如上。
- AIC-066 的 query/index 结论依赖当前 schema、数据分布和 EXPLAIN；没有声称所有生产规模都已证明。
- provider-account create/update/archive 尚未产生完整 audit producer；
- allocation-rule publish/archive 尚未产生完整 audit producer；
- logout / session-revocation / password-change 的 org_id=NULL 事件不会出现在 org-scoped audit query；
- reconciliation / close / session evidence 部分仍以 flow-level 证据为主，不是每个底层事件的直接 assertion。

这些 follow-ups 不阻塞本 RC 的 AIC-071 / AIC-072 范围，但必须在后续 issue / acceptance 中继续处理。

## 10. Final boundary

AIC-073: FROZEN / NOT EXECUTED

Human acceptance: PENDING

v1.0.0 tag: NOT CREATED

GitHub Release: NOT CREATED

Merge to main: NOT EXECUTED

AIC-072 的代码、文档、测试和 Compose evidence 已准备为 Draft PR；最终 V1 发布仍以人工验收和后续明确发布动作作为 gate。

AIC-073 Final Human Acceptance / Release Sign-off 待正式收尾。Browser UAT 技术验证已完成（32/32 PASS, 14/14 PASS, P0/P1=0），但 AIC-073 正式流程尚未在 GitHub 中关闭。
