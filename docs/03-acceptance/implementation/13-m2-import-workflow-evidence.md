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

## 7. 验证记录

（Task 17 fresh verification 后填写实际命令与计数。）

## 8. 交付确认

- [ ] 无 docs/superpowers/** 进入实现分支
- [ ] 无 Import Confirm / READY_FOR_REVIEW / Canonical Facts / Allocation / Ledger
- [ ] 无 provider live API client / credential storage
- [ ] V7 为唯一新增 migration（index-only）
- [ ] 未 push / 未创建 PR / 未 merge / 未 rebase
