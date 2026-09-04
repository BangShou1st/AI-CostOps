# M14 Multi-provider Routing / Resilience 验收证据

## 版本与范围

- 工作分支：`feat/m14-multi-provider-routing-resilience`
- 基线：`origin/main`，merge-base 为 `3eee76d`
- Implementation SHA：`8c11044`
- Evidence/docs-only SHA：本次最终 docs-only refresh commit（在最终报告中记录其提交号）
- GitHub Issue：`#145`
- 未修改 `main`，未执行 merge。

本次交付覆盖冻结设计中的 Task 1 → Task 15：V22 schema/backfill、routing policy control plane、确定性候选选择、MiMo/OpenAI adapter、Redis/local circuit breaker、SAFE failover、streaming、replay、crash recovery、Close 与 M13 settlement continuity。

## Schema 与 migration

- 新增唯一 migration：`backend/src/main/resources/db/migration/V22__m14_multi_provider_routing.sql`。
- `routing_policy` 与 `routing_policy_candidate` 已落地；通过生成的 `project_scope_key` 处理 MySQL `NULL` unique 语义，保证 org-default 的 version uniqueness 与 exact-scope 单 ACTIVE 约束。
- `gateway_route_attempt` 增加 M14 route reason、同组织 policy 外键、状态约束与查询索引。
- V22 包含现有 eligible MiMo org-default policy/candidate backfill，避免单 MiMo 旧租户升级后失去路由。
- backend 全量集成测试在干净 MySQL 8.4 Testcontainers 上验证 Flyway `22 migrations`，V1–V22 成功应用；migration 目录相对 `origin/main` 仅新增 V22。

## Routing policy、API 与 UI

- ACTIVE policy precedence：exact project →（仅 exact 不存在时）org-default → fail closed；exact policy 存在但 candidates 不可用时不会 fallback。
- request 首次选择后冻结 policy id/version；SAFE failover 继续使用同一冻结 policy，即使管理员随后激活新 revision。
- candidate 排序固定为 `priority ASC, candidate id ASC`，同一 request 每个 candidate 最多一次；没有随机、cost/latency scoring、RL 或通用 DSL。
- API 覆盖 list/detail/history、create revision、candidate edit、activate/readiness；权限复用 `PROVIDER_ACCOUNT_READ` / `PROVIDER_ACCOUNT_MANAGE`。
- 前端 `/settings/routing-policies` 使用中文 UI，支持 DRAFT/ACTIVE/RETIRED 语义、revision、candidate priority/status、readiness warnings；ACTIVE/RETIRED 只读。
- UI/API 验收确认 routing policy 可 revision/activate，页面出现 `路由策略` 与 `已启用`，响应与页面不暴露 provider secret。

## Provider certification

- Registry 固定注册 `MIMO → MimoChatAdapter`、`OPENAI → OpenAiChatAdapter`；重复 adapter 在启动时失败；缺少 adapter 的 candidate 不可选。
- `ProviderCallContext` 为 provider-neutral，不含 generic `providerKeyHeader`；secret 不进入 log、metric label、API response、frontend、audit、exception body、toString 或 route fact。
- MiMo：adapter 自行设置 API key header，覆盖 non-streaming、SSE usage/terminal parsing、保守 response matrix；没有 provider-level retry。
- OpenAI compatibility surface：adapter 使用数据库冻结的 `provider_model_name`，Bearer auth，non-streaming/SSE，`stream_options.include_usage=true`，terminal usage normalization，provider `x-request-id` correlation 与 bounded `X-Client-Request-Id`；生产 endpoint allowlist 与 bounded/redacted error handling 已实现。
- 测试均使用 mock upstream/Testcontainers；本地 CI 验证未依赖真实 OpenAI key 或真实外网 Provider call。

## Safety matrix 与 dispatch fence

- `TX2 committed → DISPATCH_INTENT → request UPSTREAM_ACTIVE`；Provider I/O 前不预先写 `BILLABLE_POSSIBLE`。
- 只有正向 `SAFE_NO_BILLABLE_EXECUTION` 证据允许换下一个 candidate；未知情况统一按 `BILLABLE_POSSIBLE` 处理。
- SAFE 证据限于 pre-network/pre-write 的本地校验、DNS failure、connection refused、connect timeout、TLS handshake failure；HTTP 429/5xx、read/idle/hard timeout、write 后 reset、malformed response、generic I/O/timeout、client cancel 等均不会自动 failover。
- `DispatchFenceService` 阻止 replay/非 server-owned attempt 创建重复 Provider dispatch；同一个 client idempotency key 不因 failover 创建并行 attempt。
- streaming 仅允许首个 candidate 在 pre-write SAFE 且未向下游发出 delta 时切换；禁止 A partial output 与 B output 混合；`[DONE]` 仍在 usage/lifecycle durable commit 后下发。

## Budget、SAFE failover 与 recovery

- candidate admission failure 不再把整个 request 直接标为 `REJECTED_BUDGET`；A 不足时 A attempt SAFE、释放 A reservation 后才尝试 B。
- 每个 B candidate 重新解析 pricing version，并在同一 request billing period 内寻找对应 currency budget；禁止 FX、reservation resize/retarget/reuse。
- 强制顺序已实现并由真实 MySQL 集成测试覆盖：

  `A positive SAFE → durable A SAFE → release A → verify no ACTIVE/PENDING_HOLD → same frozen policy → fresh pricing → B attempt → fresh admission/reservation → B TX2 DISPATCH_INTENT → one B call`

- 财务锁序保持 `BillingPeriod → Budget → Reservation`；M13 settlement 使用实际 billable attempt 的 frozen pricing version。
- recovery 只释放有正向 SAFE/definitive pre-dispatch 证据的 hold，进程死亡时不在后台发起 Provider call；全 SAFE 链收敛为 no-charge terminal failure。
- Close 忽略 SAFE + RELEASED 历史链，但继续阻塞 ACTIVE/PENDING_HOLD、未解决 BILLABLE_POSSIBLE、未知 usage、未 settlement 及 pending/retryable/reconciliation settlement。

## Circuit breaker 与 M13 continuity

- Redis key scope：`org_id/provider_account_id/provider_model_id`；状态 CLOSED/OPEN/HALF_OPEN。
- 默认 failure threshold=5、OPEN duration=30s、HALF_OPEN probe lease=15s；Redis Lua 原子计数/开启与 probe lease，多副本只有一个 probe owner；Redis 不可用时退化到 bounded local breaker。
- circuit 只影响未来 candidate selection，不修改 Route Attempt、Reservation、Usage Fact、Settlement、Ledger；HALF_OPEN probe 使用正常用户请求。
- 多个 historical SAFE attempts 可存在；最多一个 possibly-billable/completed attempt 产生 Usage Fact、Settlement、Ledger posting、Budget Actual。

## 实际执行的验证

以下均为本地实际执行结果：

| 范围 | 命令/验证 | 结果 |
|---|---|---|
| backend unit/integration/architecture | `backend\\mvnw.cmd -B verify` | `905 tests`, 0 failures, 0 errors, 0 skipped；BUILD SUCCESS |
| gateway unit/integration/architecture | `gateway\\mvnw.cmd -B verify` | `127` unit + `63` integration，0 failures/errors/skipped；BUILD SUCCESS |
| gateway focused safety | `OpenAiChatAdapterTest`, `CandidateEligibilityEvaluatorTest`, `GatewaySafeFailoverIntegrationTest`, `ReservationRecoveryIntegrationTest`, `ChatCompletionControllerTest`, `CircuitBreakerRedisIntegrationTest` | 通过 |
| replay/streaming | `GatewayRequestIdempotencyIntegrationTest`, `MimoStreamingIntegrationTest`, `StreamingLifecycleIntegrationTest` | 分别 `3/3`、`9/9`、`2/2` 通过 |
| MySQL concurrency repeat | Failsafe `GatewaySafeFailoverIntegrationTest`，真实 MySQL/Redis Testcontainers，连续 5 次 | 每次 `3/3`，共 `15/15` 通过 |
| Redis multi-instance | `CircuitBreakerRedisIntegrationTest` | 通过 |
| frontend unit | `npm test -- --run --maxWorkers=1` | `48` files、`434` tests 通过 |
| frontend lint | `npm run lint` | 通过 |
| frontend build | `npm run build` | 通过；仅有既有 bundle size warning |
| browser E2E | `AICOSTOPS_E2E_BASE_URL=http://localhost:18080; npm run test:e2e`，干净 isolated Compose project | `6 passed` |
| Docker | backend/gateway/frontend 三个 Docker build，分别使用 `ai-costops-backend:m14-local`、`ai-costops-gateway:m14-local`、`ai-costops-frontend:m14-local` | 全部通过 |
| whitespace/markers | `git diff --check`；检查 `.retry(`、`retryWhen(`、`M11_PROVIDER_CODE`、`providerKeyHeader`、`TODO`、`TBD`、`FIXME` | 无命中、无 whitespace error |

前端 Vitest 并行运行曾出现 1 个 reconciliation 测试环境抖动；针对该测试单独复现通过，随后单线程全量 `48/48`、`434/434` 通过。测试期间的 jsdom `getComputedStyle` 与 Mockito dynamic-agent 提示均为 warning，不是失败。

## Hosted CI 与 Security

- PR：[#146](https://github.com/BangShou1st/AI-CostOps/pull/146)，通过 `Refs #145` 关联 Issue #145。
- CI run [`33923415512`](https://github.com/BangShou1st/AI-CostOps/actions/runs/33923415512)：backend/gateway unit、integration、architecture，frontend lint/test/build，browser-e2e 与 docker-build 全部 pass。
- Security run [`33923415536`](https://github.com/BangShou1st/AI-CostOps/actions/runs/33923415536)：CodeQL Java/Kotlin、CodeQL JavaScript/TypeScript 与 Trivy 全部 pass。
- 两个 run 均验证最终 HEAD `8c1104432e7c8cc14d98d8c1bd92bc5588569b92`。

## Secret/content redaction 与 UAT

- evidence 不包含 provider secret、raw gateway key、完整 upstream prompt/completion 或 token；测试只使用 bounded mock payload/headers。
- browser UAT 已验证登录后可进入 `/settings/routing-policies`、policy revision/activation 成功、ACTIVE 状态可见，页面 body 不包含 `sk-`。
- 本地 Compose clean stack 使用开发 bootstrap 生成 ACTIVE org-default routing policy，E2E 完成后保留原开发栈数据，不删除原有 volumes。

## Remaining concerns

- 未执行真实 OpenAI 外网调用，这是设计要求；OpenAI 行为由 mock upstream certification 覆盖。
