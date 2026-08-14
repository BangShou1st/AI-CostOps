# 03. M2 Evidence & Import API

> M2 Group 1（AIC-021 ~ AIC-024）对外只暴露两个端点：Provider Import 创建与
> Evidence 授权下载。列表/详情/重试/取消属于 AIC-030，Import Confirm 属于 M3。
> Group 1 没有生产 Provider Adapter（DeepSeek/MiMo/Kimi/GLM/OpenAI 在 Group 2 接入）。

## 1. 通用安全语义

沿用 M1：

```text
缺少权限           -> 403 FORBIDDEN
不存在 / 跨组织     -> 404 RESOURCE_NOT_FOUND（隐私保护，不区分两者）
状态冲突           -> 409 STATE_CONFLICT
依赖暂不可用       -> 503 DEPENDENCY_TEMPORARILY_UNAVAILABLE
```

Evidence / Import 相关权限要求 `permission + ORG scope`。

## 2. POST /api/v1/provider-imports

创建（或幂等复用）Provider Import。

### 权限

```text
EVIDENCE_UPLOAD_PROVIDER + ORG scope
```

### 请求

`multipart/form-data`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `file` | file | Provider Evidence 文件（流式上传，禁止整文件读入内存） |
| `providerAccountId` | long | 当前组织 ACTIVE Provider Account |
| `sourceType` | enum | `FILE_EXPORT` / `USAGE_API_JSON` / `COSTS_API_JSON` |

请求组织永远来自认证上下文，不接受客户端传入 org ID。

### 处理顺序

```text
auth + require ORG / EVIDENCE_UPLOAD_PROVIDER
-> ACTIVE Provider Account（不可用 / 跨组织 -> 404）
-> registry 解析 adapter / parserVersion（未注册 -> 400，且不创建任何行）
-> store / reuse Evidence
-> create / reuse ImportBatch
   new Batch  -> 创建 Attempt #1 INITIAL QUEUED
   existing   -> 返回现有 Batch 与最新 Attempt，不隐式重试
```

### 响应 201

```json
{
  "evidenceId": 1,
  "importBatchId": 2,
  "latestAttemptId": 3,
  "batchStatus": "PENDING",
  "duplicateEvidence": false,
  "duplicateBatch": false
}
```

Identity 规则：

```text
same org + same SHA-256        -> 同一 Evidence（跨 org 不做物理去重）
Evidence + ProviderAccount
  + sourceType + parserVersion -> 同一 ImportBatch（不同 parser version 是不同 Batch）
```

上传硬上限默认 512 MiB（`AICOSTOPS_STORAGE_UPLOAD_LIMIT`），超限：

```text
413 PAYLOAD_TOO_LARGE, code=EVIDENCE_TOO_LARGE
```

## 3. GET /api/v1/evidence/{id}/download

授权下载原始 Evidence 字节（对象流式返回）。

### 权限

```text
EVIDENCE_DOWNLOAD + ORG scope
```

### 语义

```text
跨组织 / 不存在          -> 404 RESOURCE_NOT_FOUND
Evidence 非 AVAILABLE    -> 409 STATE_CONFLICT（不打开对象流）
成功                     -> 200 原始字节
```

响应头：

```text
Content-Type:     存储的 media_type（缺省 application/octet-stream）
Content-Disposition: attachment; filename="<清洗后的 original_filename>"
```

对象键、MinIO 端点、凭据永不出现在响应中。

## 4. 状态机摘要（M2 范围内）

```text
ImportBatch: PENDING -> PROCESSING -> PARSED | FAILED
                        └--> CANCELED（AIC-030 保留，M2 不暴露操作）

ImportAttempt: QUEUED -> RUNNING -> SUCCEEDED | FAILED
                            └>（lease 过期）FAILED(WORKER_LEASE_EXPIRED)
                               -> successor LEASE_RECOVERY QUEUED（预算 3 次）
```

- 只有 WARN issue 的 Attempt 可 `SUCCEEDED`，Batch `PARSED`。
- 存在 ERROR issue 的 Attempt `FAILED`，Batch `FAILED`。
- 重试/恢复永远创建新 Attempt，旧 Attempt / RawRecord / Issue 保留 lineage。

## 5. M2 边界（Non-goals）

本组不实现：

```text
Import list/detail、Attempt list/detail、Issue/RawRecord 浏览
Manual retry、Cancel HTTP 动作                     -> AIC-030
Import Confirm、READY_FOR_REVIEW                   -> M3
canonical cost facts、allocation、ledger           -> M3
生产 Provider Adapter                              -> Group 2
```

## 6. 权限增量

V5 migration：`FINANCE_REVIEWER` 增加 `PROVIDER_ACCOUNT_READ`
（用于选择/读取 Provider Account 发起导入），不增加 `PROVIDER_ACCOUNT_MANAGE`。
