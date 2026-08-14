# 11. M2 Group 1 Evidence & Import Foundation — Acceptance Evidence

> Date: 2026-08-14
> Branch: `feat/m2-evidence-import-foundation`
> Baseline: `0b3864f feat(m1): complete organization and authorization end-to-end (#28)`

## 1. Issue 对应关系（官方含义）

```text
AIC-021 = Evidence / Import Schema
AIC-022 = S3-compatible Evidence Storage
AIC-023 = DB-backed Import Worker
AIC-024 = ProviderAdapter Registry / Schema Inspection
```

本证据覆盖 AIC-021 ~ AIC-024 的实现与独立 Review Fix Round。

## 2. 验证命令与真实结果（Fix Round 后最终执行）

### Backend unit suite

```powershell
Set-Location E:\AI-CostOps\backend
.\mvnw.cmd -B "-DexcludedGroups=architecture,integration" test
```

```text
Tests run: 157, Failures: 0, Errors: 0, Skipped: 1
BUILD SUCCESS
```

### Full backend integration suite（MySQL 8.4 + MinIO Testcontainers）

```powershell
.\mvnw.cmd -B "-Dgroups=integration" verify
```

```text
Tests run: 208, Failures: 0, Errors: 0, Skipped: 0
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

### 3.1 Schema / 约束（V4 + V5 + V6）

`M2EvidenceImportSchemaIntegrationTest`：Tests run 8, Failures 0。

```text
- 5 张表存在：evidence / import_batch / import_attempt / raw_provider_record / import_issue
- 7 个索引：uq_evidence_org_sha256、uq_import_batch_identity、
  uq_import_attempt_batch_no、idx_import_attempt_queue、idx_import_attempt_lease、
  idx_import_attempt_batch_status、uq_raw_provider_record_attempt_index
- 11 个 FK 经 information_schema.key_column_usage 验证为真实引用关系
- Batch identity = UQ(evidence_id, provider_account_id, source_type, parser_version)
- V6 usage window CHECK：usage_start/usage_end 均非 NULL 时 start <= end；
  合法范围（start<end、open start）可插入，start>end 被约束拒绝
```

`RolePermissionSeedIntegrationTest`：Tests run 2, Failures 0。

```text
- FINANCE_REVIEWER 增加 PROVIDER_ACCOUNT_READ
- 断言 FINANCE_REVIEWER 不包含 PROVIDER_ACCOUNT_MANAGE
```

### 3.2 Evidence 身份 / 去重 / 存储恢复（AIC-021/022）

`EvidenceStorageServiceIntegrationTest`：Tests run 12, Failures 0。

```text
- same org + same SHA-256 -> 同一 Evidence id，且对象只写一次
- 不同 org 相同字节 -> 独立 Evidence / 独立对象命名空间
- 对象存储 put 期间 TransactionSynchronizationManager.isActualTransactionActive()==false
- STAGING + 已存在匹配对象 -> AVAILABLE 修复，不重写
- FAILED Evidence 修复 -> AVAILABLE，且报告 reused identity
- deterministic key 上 metadata 不匹配 -> 409 STATE_CONFLICT，字节绝不被覆盖
- 存储失败 -> FAILED(STORAGE_UPLOAD_FAILED)；AVAILABLE 永不被晚到失败降级
- duplicate 标志真实反映 identity reuse（new=false / AVAILABLE reuse=true /
  STAGING repair=true / FAILED repair=true），不通过 storage status 猜测
```

`MinioObjectStorageAdapterIntegrationTest`：Tests run 4, Failures 0。

```text
- put/stat/open 精确字节 + 显式 SHA-256 user metadata
- 缺失对象 stat -> empty
- bucket 懒初始化；全新未创建 bucket 上两个线程并发首次操作
  （double-check + synchronized）均成功且 bucket 恰好存在
- M1 上下文启动无需 MinIO（回归通过）
```

### 3.3 Evidence 授权下载（AIC-022）

`EvidenceDownloadApiIntegrationTest`：Tests run 6, Failures 0。

```text
- Finance 角色 + EVIDENCE_DOWNLOAD + ORG scope -> 200 精确字节
- 仅有非 ORG scope -> 403 FORBIDDEN；缺权限 -> 403 FORBIDDEN
- 跨组织 -> 404 RESOURCE_NOT_FOUND
- STAGING / FAILED -> 409 STATE_CONFLICT，且不打开对象流
- AVAILABLE 但对象缺失 -> 503 DEPENDENCY_TEMPORARILY_UNAVAILABLE（不泄露对象键）
```

### 3.4 Provider Import 创建（幂等 + 依赖 503）（AIC-023）

`ProviderImportApiIntegrationTest`：Tests run 8, Failures 0（test-only TEST_PROVIDER）。

```text
- 新上下文 -> Evidence + Batch(PENDING) + Attempt #1 QUEUED
- 相同 bytes/account/source/parser -> 相同 Evidence/Batch/Attempt，无隐式重试
- 相同 bytes + 不同 Provider Account -> 相同 Evidence，不同 Batch
- 权限非 ORG scope -> 403；inactive/cross-org account -> 404
- 未注册 provider -> 400 VALIDATION_FAILED 且零写入
- 缺上传权限 -> 403
- 并发相同 identity 上传 -> 收敛为一个 Batch/Attempt；
  恰好一个请求报告 duplicateEvidence=true（loser 语义）
```

`ProviderImportStorageOutageApiIntegrationTest`：Tests run 1, Failures 0。

```text
- Object Storage dependency outage（stat 抛 ObjectStorageException）
  -> 503 DEPENDENCY_TEMPORARILY_UNAVAILABLE（非 500，不暴露 MinIO 原始 message）
- stat outage 不破坏 Evidence 状态：row 保持 STAGING 供后续重试修复
```

### 3.5 MySQL 租约 / fencing / 恢复（AIC-023）

`ImportLeaseServiceIntegrationTest`：Tests run 12, Failures 0。

```text
- 一个 QUEUED 只被一个 worker claim；两个 QUEUED 被两个并发 worker 各 claim 一个
- heartbeat 用 DB 时钟续租；stale owner / stale lease_version 不能 heartbeat
- available_at 未来不 claim；lease 时长由 DB 时钟计算；lease_version 每次递增
- expired RUNNING -> FAILED(WORKER_LEASE_EXPIRED) + successor QUEUED
  （attempt_no+1、trigger=LEASE_RECOVERY、predecessor=旧 id、Batch PENDING）
- 失败 Attempt 的 raw rows 保留；恢复预算（3 次）耗尽 -> Batch FAILED
- 未过期 RUNNING 不被恢复；并发恢复恰好一个 successor
```

### 3.6 有界脱敏 RawRecord / Issue 持久化（AIC-024）

`ImportAttemptExecutorIntegrationTest`：Tests run 16, Failures 0。

```text
- 500 条 -> 单个原子有界批次；501 条 -> 第二批 1 条
- stale lease -> 0 插入且报告 lease lost
- 部分批次 rows 在后续 Attempt 失败后保留
- raw payload secret-like 字段（api_key / password_hash / refresh_token /
  client.secret）在 DB 中为 [REDACTED]，安全字段原样保留
- WARN/ERROR counters 精确
- WARN-only -> Attempt SUCCEEDED / Batch PARSED
- 不兼容 schema -> FAILED(SCHEMA_INCOMPATIBLE) / Batch FAILED + ERROR issue
- parse 中途失败 -> 已 flush 的 raw rows 保留，Attempt FAILED(EXECUTION_FAILED)
- schema_fingerprint / detected_provider_code / parser_version 持久化在 Attempt
- stale owner 不能 finalize
```

### 3.7 Worker 生命周期（AIC-023）

`ImportWorkerCoordinatorIntegrationTest`：Tests run 2, Failures 0。

```text
- pollOnce() 直接调用；CountDownLatch 证明 TaskExecutor 分发；最终 SUCCEEDED/PARSED
- executor 饱和度：本地 Semaphore 耗尽时不再 claim（第三个 batch 保持 QUEUED）
```

### 3.8 Review Fix Round 新增证据

#### multi-active heartbeat（CRITICAL）

`ImportAttemptExecutorIntegrationTest` 新增 2 个测试：

```text
- 两个 ImportAttempt 同时进入 parse（latch 同步），heartbeatActiveExecutions()
  使两个 lease_until 都实际延后
- 一个执行结束（finally 清理）后，仍 active 的另一个执行继续被 heartbeat
  （ConcurrentHashMap<attemptId, lease> + remove(key, value)，不再互相覆盖）
```

#### atomic finalization（CRITICAL）

`ImportAttemptExecutorIntegrationTest` 新增 1 个测试：

```text
- 临时 CHECK 约束强制 Batch PARSED 更新失败 -> finalization 事务整体回滚：
  Attempt 仍 RUNNING、Batch 仍 PROCESSING（绝不出现 SUCCEEDED + PROCESSING）
- ImportAttemptFinalizationService 以单事务执行 fenced Attempt 状态写 +
  Batch 状态写；lease lost 时零变更
```

#### secret fail-closed（IMPORTANT）

`ImportAttemptExecutorIntegrationTest` 新增 2 个测试：

```text
- adapter issue message 含 password=/token=/api_key=/Authorization: Bearer、
  rawValueMasked 含真实 secret -> DB 中零命中，message 保留诊断但 secret
  片段替换为 [REDACTED]，raw_value_masked 非严格掩码一律 [REDACTED]
- parse exception message 含 secret -> error_summary 为稳定分类文案
  "Provider import execution failed (IllegalStateException)."，不存原始 message
```

#### upload dependency 503（IMPORTANT）

`ProviderImportStorageOutageApiIntegrationTest`：见 3.4。

#### concurrent MinIO init（IMPORTANT）

`MinioObjectStorageAdapterIntegrationTest`：见 3.2。

#### usage window CHECK（SCHEMA）

`M2EvidenceImportSchemaIntegrationTest`：见 3.1（V6，forward-only）。

#### config 严格校验（CONFIG）

`ImportWorkerPropertiesTest`（unit，5/5）：

```text
- poll-interval / lease-duration / heartbeat-interval 必须严格 > 0
- heartbeat-interval 必须严格 < lease-duration
- 正常 1s / 60s / 20s 组合接受
```

#### Evidence reused flag（API SEMANTICS）

见 3.2：reservation 显式返回 reusedExistingIdentity（并发 loser 亦为 true）。

### 3.9 模块边界

`ModuleDependencyArchitectureTest`：Tests run 4, Failures 0。

```text
- evidence.. 不得依赖 ingestion..
- ingestion.. 不得依赖 ledger/budget/attribution/reporting..
- ingestion.application.. 只允许依赖 ingestion/evidence/organization/iam/shared + 基础库
```

## 4. 已知偏差

1. **MinIO 镜像 tag**：Implementation Plan 指定
   `minio/minio:RELEASE.2025-10-15T17-29-55Z`，该 tag 在 docker.io/daocloud 与
   quay.io 均不存在。改用 quay.io 上存在的最近正式 release
   `quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z`。
2. **Spring DataSize**：`512MiB` 后缀不被 Spring `DataSize` 识别，配置使用
   `512MB`（Spring 语义即二进制 1024² 字节）。
3. **M1AdminPermissionPolicy 扩展**：M2 Evidence/Import 权限加入权限→scope 策略
   注册表；`/api/v1/auth/me` 相应展示，MeApiIntegrationTest 期望已同步更新。
4. **okhttp-jvm**：MinIO 9.0.1 依赖 okhttp 5.3.2 KMP root artifact（无 class），
   pom.xml 显式添加 `okhttp-jvm:5.3.2` 并排除空壳 okhttp。
5. **MySQL CHECK 约束违规翻译**：MySQL error 3819 被 Connector/J 翻译为
   `UncategorizedSQLException`（非 DataIntegrityViolationException），
   约束拒绝类测试按异常消息断言约束名。

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
