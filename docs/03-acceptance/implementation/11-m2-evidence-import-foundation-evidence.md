# 11. M2 Group 1 Evidence & Import Foundation — Acceptance Evidence

> Date: 2026-08-14
> Branch: `feat/m2-evidence-import-foundation`
> Scope: AIC-021 Evidence identity/storage, AIC-022 Provider import creation,
> AIC-023 Lease/fencing/recovery worker, AIC-024 RawRecord/Issue + adapter framework
> Baseline: `0b3864f feat(m1): complete organization and authorization end-to-end (#28)`

## 1. 执行摘要

M2 Group 1 完成：V4/V5 migration、Evidence 流式上传/去重/恢复、S3-compatible
MinIO 存储、授权下载 API、Provider Import 幂等创建、MySQL `FOR UPDATE SKIP
LOCKED` 租约/fencing/恢复 worker、有界脱敏 RawRecord/Issue 持久化、ProviderAdapter
注册表框架。M2 止步于 `ImportBatch.PARSED / FAILED`。

## 2. 验证命令与真实结果

### Backend unit suite

```powershell
Set-Location E:\AI-CostOps\backend
.\mvnw.cmd -B "-DexcludedGroups=architecture,integration" test
```

```text
Tests run: 152, Failures: 0, Errors: 0, Skipped: 1
BUILD SUCCESS
```

### Full backend integration suite（MySQL 8.4 + MinIO Testcontainers）

```powershell
.\mvnw.cmd -B "-Dgroups=integration" verify
```

```text
Tests run: 197, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### Architecture suite

```powershell
.\mvnw.cmd -B "-Dgroups=architecture" test
```

```text
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### Docker / Compose

```powershell
Set-Location E:\AI-CostOps
docker compose config --quiet   # exit 0
docker build --tag ai-costops-backend:m2-group1 backend   # exit 0
```

## 3. 按行为记录的验收证据

### 3.1 Schema / 约束（V4 + V5）

`M2EvidenceImportSchemaIntegrationTest`：Tests run 7, Failures 0。

```text
- 5 张表存在：evidence / import_batch / import_attempt / raw_provider_record / import_issue
- 7 个索引：uq_evidence_org_sha256、uq_import_batch_identity、
  uq_import_attempt_batch_no、idx_import_attempt_queue、idx_import_attempt_lease、
  idx_import_attempt_batch_status、uq_raw_provider_record_attempt_index
- 11 个 FK 经 information_schema.key_column_usage 验证为真实引用关系
- Batch identity = UQ(evidence_id, provider_account_id, source_type, parser_version)
```

`RolePermissionSeedIntegrationTest`：Tests run 2, Failures 0。

```text
- FINANCE_REVIEWER 增加 PROVIDER_ACCOUNT_READ
- 断言 FINANCE_REVIEWER 不包含 PROVIDER_ACCOUNT_MANAGE
```

### 3.2 Evidence 身份 / 去重 / 存储恢复

`EvidenceStorageServiceIntegrationTest`：Tests run 11, Failures 0。

```text
- same org + same SHA-256 -> 同一 Evidence id，且对象只写一次
- 不同 org 相同字节 -> 独立 Evidence / 独立对象命名空间
- 对象存储 put 期间 TransactionSynchronizationManager.isActualTransactionActive()==false
  （对象 I/O 不在 DB 事务内）
- STAGING + 已存在匹配对象（大小 + 显式 SHA-256 metadata）-> AVAILABLE 修复，不重写
- deterministic key 上 metadata 不匹配 -> 409 STATE_CONFLICT，字节绝不被覆盖
- 存储失败 -> FAILED(STORAGE_UPLOAD_FAILED)；AVAILABLE 永不被晚到失败降级
```

`MinioObjectStorageAdapterIntegrationTest`：Tests run 3, Failures 0。

```text
- put/stat/open 精确字节 + 显式 SHA-256 user metadata
- 缺失对象 stat -> empty
- bucket 懒初始化（首次操作）；M1 上下文启动无需 MinIO（回归通过）
```

### 3.3 Evidence 授权下载

`EvidenceDownloadApiIntegrationTest`：Tests run 5, Failures 0。

```text
- Finance 角色 + EVIDENCE_DOWNLOAD + ORG scope -> 200 精确字节
- 仅有非 ORG scope -> 403 FORBIDDEN
- 缺权限 -> 403 FORBIDDEN
- 跨组织 -> 404 RESOURCE_NOT_FOUND
- STAGING / FAILED -> 409 STATE_CONFLICT，且不打开对象流
```

### 3.4 Provider Import 创建（幂等）

`ProviderImportApiIntegrationTest`：Tests run 7, Failures 0（test-only TEST_PROVIDER
adapter；生产 Group 1 注册表为空）。

```text
- 新上下文 -> Evidence + Batch(PENDING) + Attempt #1 QUEUED
- 相同 bytes/account/source/parser -> 相同 Evidence/Batch/Attempt，无隐式重试
  （import_attempt 行数仍为 1）
- 相同 bytes + 不同 Provider Account -> 相同 Evidence，不同 Batch
- 权限非 ORG scope -> 403；inactive/cross-org account -> 404
- 未注册 provider -> 400 VALIDATION_FAILED 且不创建任何 Evidence/Batch
- 缺上传权限 -> 403
```

### 3.5 MySQL 租约 / fencing / 恢复

`ImportLeaseServiceIntegrationTest`：Tests run 12, Failures 0。

```text
- 一个 QUEUED 只被一个 worker claim 到（第二个 worker 得到 empty）
- 两个 QUEUED 被两个并发 worker 各 claim 一个（真实双线程）
- heartbeat 用 DB 时钟续租（lease_until 延后）
- stale owner / stale lease_version 不能 heartbeat
- available_at 未来 -> 不 claim
- lease 时长由 DB 时钟计算（TIMESTAMPDIFF 验证）
- lease_version 每次 claim 递增
- expired RUNNING -> FAILED(WORKER_LEASE_EXPIRED) + successor QUEUED
  （attempt_no+1、trigger=LEASE_RECOVERY、predecessor=旧 id、Batch PENDING）
- 失败 Attempt 的 raw rows 保留
- 恢复预算（3 次）耗尽 -> Batch FAILED，无 successor
- 未过期 RUNNING 不被恢复
- 并发恢复 -> 恰好一个 successor
```

### 3.6 有界脱敏 RawRecord / Issue 持久化

`ImportAttemptExecutorIntegrationTest`：Tests run 11, Failures 0。

```text
- 500 条 -> 单个原子有界批次；501 条 -> 第二批 1 条
- stale lease -> 0 插入且报告 lease lost
- 部分批次 rows 在后续 Attempt 失败后保留
- secret-like 字段（api_key / password_hash / refresh_token / client.secret）在
  DB 中为 [REDACTED]，安全字段原样保留
- WARN/ERROR counters 精确（records_seen/valid、warning_count、error_count）
- WARN-only -> Attempt SUCCEEDED / Batch PARSED
- 不兼容 schema -> FAILED(SCHEMA_INCOMPATIBLE) / Batch FAILED + ERROR issue
- parse 中途失败 -> 已 flush 的 2 条 raw rows 保留，Attempt FAILED(EXECUTION_FAILED)
- schema_fingerprint / detected_provider_code / parser_version 持久化在 Attempt
- stale owner 不能 finalize（Attempt 保持 RUNNING，0 行写入）
```

### 3.7 Worker 生命周期（TaskExecutor）

`ImportWorkerCoordinatorIntegrationTest`：Tests run 2, Failures 0。

```text
- pollOnce() 直接调用（不等待 scheduler tick）；CountDownLatch 证明 TaskExecutor
  分发；任务完成后 Attempt SUCCEEDED / Batch PARSED，lease_owner 为 coordinator
- executor 饱和度：本地 Semaphore 耗尽时第三个 poll 不 claim（第三个 batch 保持
  QUEUED），并发数 = worker-concurrency
```

测试默认 `worker-enabled=false`；coordinator 测试显式开启。

### 3.8 模块边界

`ModuleDependencyArchitectureTest`：Tests run 4, Failures 0。

```text
- evidence.. 不得依赖 ingestion..
- ingestion.. 不得依赖 ledger/budget/attribution/reporting..
- ingestion.application.. 只允许依赖 ingestion/evidence/organization/iam/shared + 基础库
```

### 3.9 单元测试

```text
EvidenceUploadStagerTest（5/5）：已知 SHA、temp 清理、limit+1 拒绝(413)、
  拒绝整文件 readAllBytes、中途失败清理

ProviderAdapterRegistryTest（5/5）：canonical 查找、未知 provider empty、
  空注册表合法、重复注册启动错误、不同 code 共存
```

## 4. 已知偏差

1. **MinIO 镜像 tag**：Implementation Plan 指定
   `minio/minio:RELEASE.2025-10-15T17-29-55Z`，该 tag 在 docker.io/daocloud 与
   quay.io 均不存在（quay.io 官方列表无此 release）。改用 quay.io 上存在的最近
   正式 release `quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z`
   （compose.yaml 与 MinioContainerSupport 一致），保留"固定 release tag"意图。
2. **Spring DataSize**：`512MiB` 后缀不被 Spring `DataSize` 识别（仅
   B/KB/MB/GB/TB），配置值使用 `512MB`（Spring 语义即二进制 1024² 字节）。
3. **M1AdminPermissionPolicy 扩展**：M2 Evidence/Import 权限（EVIDENCE_*、
   IMPORT_*）加入权限→scope 策略注册表（M2 服务复用 `M1AuthorizationService
   .requireOrg` 的前提）；`/api/v1/auth/me` 相应展示这些 ORG scope 权限，
   MeApiIntegrationTest 期望已同步更新。
4. **okhttp-jvm**：MinIO 9.0.1 的 pom 依赖 okhttp 5.3.2 KMP root artifact（无
   class 文件），pom.xml 显式添加 `okhttp-jvm:5.3.2` 并排除空壳 okhttp。

## 5. Non-goals 确认

```text
无 canonical cost 表 / 类型（external_document/consumption_fact/pricing_fact/
  charge_fact/attribution_hint 未创建）
无 READY_FOR_REVIEW / Confirm 实现
无生产 Provider Adapter（DeepSeek/MiMo/Kimi/GLM/OpenAI）
无跨组织 evidence 去重
无 RabbitMQ/Kafka；Redis 不承载 import job truth
无 forceful running-parser cancellation
```
