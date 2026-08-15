# 13. M2 Group 3 — Import Workflow Acceptance Evidence

> Date: 2026-08-15
> Branch: `feat/m2-import-workflow`
> Baseline: `main@215ab9a324159b64022327371d7119fbf7da0e32`
> Issues: #38 (AIC-030), #39 (AIC-031)
> 验证命令与计数在 Task 17 fresh verification 后填写；本文不包含猜测值。

## 1. Issue 映射

| Issue | AIC | 交付物 |
|---|---|---|
| #38 | AIC-030 | Import Review / Retry / Cancel API（Evidence/Import/Attempt/Issue/RawRecord 读取 + 幂等 Retry/Cancel + 审计） |
| #39 | AIC-031 | Evidence / Import React workflow（列表/详情、上传、轮询、历史尝试审查、原始记录抽屉） |

## 2. 交付边界

本轮交付：

```text
Provider Evidence Upload（复用 POST /api/v1/provider-imports）
Evidence List / Detail
Import List / Detail
Attempt History
Issues / Sanitized Raw Records（bounded、lazy detail）
FAILED / CANCELED → Manual Retry（新 MANUAL_RETRY Attempt，Batch → PENDING）
PENDING / PROCESSING → Cancel（协作式，fencing 保证 stale worker 零写入）
Idempotency-Key（SHA-256 指纹 + request hash）与 secret-free audit
```

明确不在本轮（M3 边界）：

```text
Import Confirm / READY_FOR_REVIEW
Canonical Facts（external_document / consumption_fact / pricing_fact /
charge_fact / attribution_hint）
Normalized Facts UI / Allocation Proposal UI / duplicate adjudication / Ledger
WebSocket / SSE / provider 实时 API 轮询 / 凭据存储
```

## 3. API 清单

```text
GET  /api/v1/evidence
GET  /api/v1/evidence/{evidenceId}
GET  /api/v1/evidence/{evidenceId}/download          (existing, EVIDENCE_DOWNLOAD)
GET  /api/v1/evidence/{evidenceId}/imports           (IMPORT_READ)
GET  /api/v1/imports
GET  /api/v1/imports/{importId}
GET  /api/v1/imports/{importId}/attempts
GET  /api/v1/imports/{importId}/attempts/{attemptId}/issues
GET  /api/v1/imports/{importId}/attempts/{attemptId}/raw-records
GET  /api/v1/imports/{importId}/attempts/{attemptId}/raw-records/{recordId}
POST /api/v1/imports/{importId}/retry                (IMPORT_RETRY, Idempotency-Key)
POST /api/v1/imports/{importId}/cancel               (IMPORT_CANCEL, Idempotency-Key)
POST /api/v1/provider-imports                        (existing, EVIDENCE_UPLOAD_PROVIDER)
```

## 4. 状态机与不变量

```text
Retry:   FAILED | CANCELED → 新 MANUAL_RETRY Attempt（attempt_no+1,
         predecessor=latest, QUEUED）→ Batch PENDING；PENDING/PROCESSING/PARSED → 409
Cancel:  (PENDING,QUEUED) | (PROCESSING,RUNNING) → Attempt CANCELED + Batch CANCELED；
         PARSED/FAILED/CANCELED → 409
旧 Attempt / RawRecord / Issue 永不删除。
锁顺序：ImportAttempt → ImportBatch（与 worker 一致）。
幂等：keyFingerprint = SHA-256(raw key) 存 idempotency_key；同 key 同 hash 重放；
     同 key 异 hash → 409；201 字符 key → 校验失败零变更。
审计：IMPORT_RETRIED / IMPORT_CANCELED，subject IMPORT_BATCH，metadata 仅 id/status。
```

## 5. 安全

```text
缺权限 → 403（先于资源查找）；跨组织/不可见 → privacy-preserving 404；
父子不匹配 → 404。
浏览器可见 ID 全部为十进制字符串（含 provider-imports 创建响应）。
Raw Record 列表仅 key 摘要（keyCount/keys<=32/keysTruncated），无 payload 值；
detail 返回持久化脱敏 payload 并在响应边界再次 PayloadRedactor。
Raw Provider Record 的 payload object-key 也属于 secret-safe review boundary：
持久化与读取边界都会把自身含 secret material 的 key（sk-...、Bearer ...、
api_key=sk-...）替换为 deterministic SHA-256 占位符（[REDACTED_KEY:<hex>]），
普通 schema key（model/usage/credentialId/api_key/token/future_note）原样保留；
legacy 行的 key 摘要同样 sanitize，不直接回传 JSON_KEYS 原文。
Evidence associated Imports 的 page/count 使用完全相同的 tenant-consistent
dataset（evidence 与 provider_account 均带 org 一致性 join）。
Evidence object key 与 worker lease 内部字段不暴露。
```

## 6. 前端行为

```text
路由 /evidence /evidence/:id /imports /imports/:id，PermissionRoute 前置门控。
仅 GET /api/v1/imports/{id} 在 PENDING/PROCESSING 每 3 秒轮询；终态停止；
unmount/logout 清理。Retry/Cancel 每次点击一个 UUID key；mutation 不自动重试；
409 展示状态变更错误并刷新，不自动重发。
Raw payload 以转义 JSON 文本渲染（无 dangerouslySetInnerHTML）。
M3 tabs/actions 不渲染。
```

## 7. 验证记录（2026-08-15 fresh verification）

```text
Backend unit (excludedGroups=architecture,integration):
  .\mvnw.cmd -B "-DexcludedGroups=architecture,integration" test
  Tests run: 329, Failures: 0, Errors: 0, Skipped: 1
  BUILD SUCCESS

Backend integration:
  .\mvnw.cmd -B "-Dgroups=integration" verify
  Tests run: 299, Failures: 0, Errors: 0, Skipped: 0
  BUILD SUCCESS

Backend architecture:
  .\mvnw.cmd -B "-Dgroups=architecture" test
  Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
  BUILD SUCCESS

Frontend:
  npm ci
  npm test -- --run           22 test files, 127 tests passed
  npm run lint                 exit 0
  npm run build                success

Container/repo:
  docker compose config --quiet            exit 0
  docker build --tag ai-costops-backend:m2-group3 backend   success
  git diff --check                         no output
```

验证中发现并修复的既有缺陷：`ImportLeaseServiceIntegrationTest.insertBatchWithQueuedAttempt`
返回值在 batchId/attemptId 语义间混用，此前依赖两表 auto_increment 同步而侥幸通过；
本分支暴露后已修复（commit 7b17233），全套 299 integration 通过。

### Independent review fix round（2026-08-15，dbb965a 之后）

- F1（BLOCKER）Raw Record 元数据脱敏：`record_locator` / `provider_record_key`
  在持久化边界与读取边界都经 `SecretShapes` redaction + VARCHAR(500) bound；
  回归测试直接持久化 `sk-SECRET-SENTINEL-DO-NOT-RETURN` 后 list/detail 均不泄露，
  `credentialId=keyid_fake` 保留（commit 9facebc）。
- F2（MAJOR）SQL 级租户边界：Import 读取全部 org-scoped，lineage join 附加
  org 一致性条件，Raw detail 用 scoped read；跨组织 lineage 异常行不可见
  （commit 9facebc）。
- F3（MAJOR）active→terminal 缓存失效：transition 检测 + 前缀 invalidate +
  command 响应 setQueryData（Retry 立即恢复轮询、Cancel 立即停止）+
  fake-timer/cache 测试（commit 0fdadf7）。
- F4（MAJOR）Import detail 读取错误态：loading/error/success 三态 + 回归测试
  （commit 0fdadf7）。
- F5（MAJOR）`/app` business-aware landing：EVIDENCE_READ/IMPORT_READ →
  business route，fallback settings，无权限 403（commit 0fdadf7）。
- F6（MAJOR）issueCode 过滤器接受任意 server-side 值（bounded text input），
  不再从当前页派生 options（commit 0fdadf7）。
- F7 cancel-first 确定性 fencing 回归 + persistence 边界脱敏证明
  （commit 5e27012）。
- F8 Retry/Cancel audit 写入失败与 mutation/idempotency 同事务回滚证明
  （commit 5e27012）。

### M2 Closure fix round（2026-08-15）

- G1（BLOCKER）secret-shaped JSON object key 三条路径全部关闭：新持久化
  （raw_payload/normalized_payload 落库前 PayloadRedactor 替换 key）、legacy
  Raw Record Detail（响应边界再次 redact）、legacy Raw Record List key 摘要
  （JSON_KEYS 结果逐个 sanitizeKey）；sanitization deterministic（SHA-256）
  且 collision-safe，普通 schema key 名称保留、secret-shaped key 的 value
  同步 fail-closed 为 [REDACTED]。RED→GREEN 回归：单元 6 个 + persistence
  DB 直查 + JDBC 直插 legacy 后 list/detail HTTP 双端点。
- G2（MAJOR）Evidence associated Imports count 与 page 同一 logical dataset：
  countEvidenceImports 补齐与 pageEvidenceImports 完全相同的 evidence /
  provider_account org 一致性 joins；回归：current-org Evidence + Batch +
  foreign-org ProviderAccount 异常 lineage 在
  GET /api/v1/evidence/{evidenceId}/imports 中既不出现于 items 也不计入
  totalElements。

## 8. 交付确认

- [ ] 无 docs/superpowers/** 进入实现分支
- [ ] 无 Import Confirm / READY_FOR_REVIEW / Canonical Facts / Allocation / Ledger
- [ ] 无 provider live API client / credential storage
- [ ] V7 为唯一新增 migration（index-only）
- [ ] 未 push / 未创建 PR / 未 merge / 未 rebase
