# 03 — 测试 / 验收包

这部分回答：

> “做完”到底怎么证明？各版本与里程碑的验收证据在哪里？

当前项目状态：

```text
FINAL_PRODUCT_VERSION = V3
STABLE_RELEASE = v3.1.2
Feature development = CLOSED
Operational mode = MAINTENANCE_ONLY
V4 = NOT_PLANNED
M20 Production Acceptance = COMPLETE
M21 Operational Readiness = COMPLETE
M22 = CLOSED WITH DEFERRED NON-BLOCKING GATES
M22_BROWSER_ORGANIZATION_ISOLATION = PASS (2026-10-19 supplementary)
DEEP_CODE_REVIEW = COMPLETE (2026-11-12, 32 findings disposed by v3.1.1)
RUNTIME_VERIFICATION = COMPLETE (2026-11-13, 2 defects fixed by v3.1.2)
Known release-blocking defects = NONE
```

M22 原始关闭记录中的两个 gate 当时均为 `DEFERRED / NON-BLOCKING`。其中 independent browser-context organization-isolation 已由 2026-10-19 补充验收完成并判定 PASS（含后续独立复审发现的模型可见性边界整改），见 [2026-10-19 M22 双组织浏览器隔离补充验收](maintenance/2026-10/2026-10-19-m22-browser-organization-isolation.md)；real-provider external credential 仍为 `DEFERRED / NON-BLOCKING`，不随浏览器隔离改为 PASS。历史记录 [M22 v3 production certification](milestones/m22-v3-production-certification.md) 保留其原时间点结论，未改写。其他里程碑见 [M20](milestones/m20-v3-production-acceptance.md) 与 [M21](milestones/m21-v3-post-release-operational-readiness.md)。下方 V1 / V1.1 条目是历史验收证据。

## 2026-10 维护验收

以下记录基于 `dev @ c5142089bb764a0c878e8761af01854b08045068` 及其近期维护提交，均为已在 `dev` 存在的维护与验收成果：

- dev 分支 CI 14/14、Security 3/3 全绿：[dev 分支 CI/Security 启用与首轮远程验收](maintenance/2026-10/2026-10-18-dev-branch-ci-security-enablement.md)
- Gateway → Usage → Settlement → Ledger → Budget Actual 财务闭环（真实 MySQL + Mock Provider）：[dev 财务闭环验收](maintenance/2026-10/2026-10-18-dev-financial-closed-loop-acceptance.md)
- M21 运维脚本、Gateway 最小权限（`LOCK TABLES`）与财务 Smoke 强化：[M21 运维验收修复](maintenance/2026-10/2026-10-19-m21-ops-acceptance-fix.md)
- M21 Advisor Worker 最小数据库权限补齐：[Advisor Worker 最小数据库权限](maintenance/2026-10/2026-10-19-advisor-worker-minimal-db-privileges.md)
- M22 双组织、双独立 `BrowserContext` 隔离及模型可见性边界整改：[M22 双组织浏览器隔离补充验收](maintenance/2026-10/2026-10-19-m22-browser-organization-isolation.md)，最新结论 `M22_BROWSER_ORGANIZATION_ISOLATION = PASS`
- OpenCode Zen minimax 模型真实浏览器验收（Edge + BrowserSkill 真实 UI + 真实上游推理）：[OpenCode Zen minimax 真实浏览器验收](maintenance/2026-10/2026-10-22-opencode-zen-minimax-real-browser-acceptance.md)
- OpenCode Zen v1.5.4 升级与真实上游 HTTP 200 验收（Backend 探针与 Gateway 聚合推理真实 200）：[OpenCode Zen v1.5.4 真实上游验收](maintenance/2026-10/2026-10-26-opencode-zen-v1.5.4-acceptance.md)
- 远端 CI / Security 红灯整改（ingestion 读 API 集成测试 ID 碰撞断言收敛 + 前端 nginx 基线升级至 1.30.6 消除 3 项 HIGH）：[CI / Security 红灯整改验收](maintenance/2026-10/2026-10-26-ci-security-red-light-remediation.md)

## 2026-11 维护验收

- **Gateway ai-model-bridge 接入（2026-11-14）**：`CUSTOM_OPENAI_COMPATIBLE` wire 层切换到 `ai-model-bridge-core v0.2.0`，删除旧 OpenAI adapter / wire DTO / SSE decoder；API、Provider Hub、预算、路由、结算与账本不变。完整 Gateway `verify` 通过（单测 155 + 集成 99），完成 10 个 adapter 定向单测（含有界背压），并用真实 Gemini / ModelScope key 验证非流式 + 流式上游路径；SSRF connect-time pinning 与 Provider metadata 按部署边界/排障需求延后，不恢复旧 Adapter：[接入验收](maintenance/2026-11/2026-11-14-gateway-ai-model-bridge.md)
- **共享库 Provider 退役（2026-11-14）**：移除 OpenCode Zen / Kilo Gateway 两个外部共享库及其 Backend/Gateway 接入，同步下线 MiMo 模型路由；新增 V33 仅禁用 catalog 行，不删除账户、连接、费用、用量或账本历史。MiMo 账单导入保留：[退役记录](maintenance/2026-11/2026-11-14-shared-library-provider-retirement.md)
- **全仓库文档维护（2026-11-13）**：版本/发布日期口径、Redis 8.8 技术事实、目录与历史路径、文档索引、发布基线与链接校验器一次性对齐；`verify:links` 扩展为全仓 Markdown + 本地图片检查；无代码、Schema 或财务语义变更：[文档维护记录](maintenance/2026-11/2026-11-13-documentation-maintenance.md)
- **核心业务真实性与产品一致性总报告**：链路 A/B 验收汇总、产品一致性审查（占位实现 / 前后端状态 / README 证据核查）、前端文案改造与截图验收、死代码清理与提交清单，总体 PASS：[核心业务真实性与产品一致性验收](maintenance/2026-11/2026-11-10-core-business-consistency-acceptance.md)
- 业务链路 B（外部账单归集与财务对账）端到端真实性验收：file_hash / 记录指纹 / posting_key 三层幂等、Worker 租约与崩溃恢复、POSTED Ledger 不可变与 Correction 语义、8 项 CloseBlocker 与 CLOSED 账期写栅栏、对账溯源与前端契约层一致性，8/8 检查点 PASS：[账单归集与财务对账链路端到端验收](maintenance/2026-11/2026-11-10-billing-chain-e2e-acceptance.md)
- 业务链路 A（AI Gateway 实时成本治理）8 检查点端到端真实性验收：预算预占 / 并发 / 中断恢复 / 未知用量 / 幂等 / 故障注入 / Redis 降级 / 工作台追溯，156 个定向复跑用例全绿（真实 MySQL 8.4 + Redis Testcontainers，模拟上游）：[Gateway 链路 E2E 验收](maintenance/2026-11/2026-11-10-gateway-chain-e2e-acceptance.md)
- CO-00 代码、分支与安全基线：分支/SHA/依赖清单、Trivy 与 CodeQL 脱敏结果、`follow-redirects` 1.16.2 与 guava/snappy 钉版，`main @ 57696e2` 的 CI 14/14 + Security 3/3 重新验证：[CO-00 基线证据](maintenance/2026-11/2026-11-11-co-00-baseline/2026-11-11-co-00-baseline.md)
- CO-01/CO-02 核心链路可靠性补强：预占前孤儿恢复、依赖不可用 503、对象不可读失败、导入失败指标与 50 并发同键：[CO-01/CO-02 补强验收](maintenance/2026-11/2026-11-11-co-01-co-02-reliability/2026-11-11-co-01-co-02-reliability.md)
- CO-03 Redis 正确性与降级行为：登录限流 Lua 原子化、工作台缓存权限指纹、Gateway Redis 命令超时，以及 Redis 职责/故障矩阵与已记录限制：[CO-03 Redis 验收](maintenance/2026-11/2026-11-11-co-03-redis/2026-11-11-co-03-redis.md)
- CO-04 SQL / Worker / 性能复验：10k/100k import 各 3 轮（289–343 rows/s）、10k reporting 30 次（p50 73 ms / p95 77 ms）、M8/M9 EXPLAIN ANALYZE；500k reporting `NOT_VERIFIED`（当前 Docker 内存不足）：[CO-04 性能验收](maintenance/2026-11/2026-11-11-co-04-sql-worker-performance/2026-11-11-co-04-sql-worker-performance.md)
- CO-05 可观测与故障注入：计划书关联键映射、traceId/MDC、导入失败与对象存储依赖指标、§8.2 故障矩阵与交付决策：[CO-05 可观测验收](maintenance/2026-11/2026-11-11-co-05-observability/2026-11-11-co-05-observability.md)
- **HC-01 Gateway 单/双实例高并发基线（Issue #176）**：真实 MySQL 8.4 / Redis 8.8 + 受控 Mock Provider、独立 JVM 单/双实例、5–200 并发 × 3 轮；热点单预算、同账期多预算、多组织对照，慢上游、SSE 完成/取消/上限、断流、同键风暴、预算耗尽与产品默认限流；逐轮预算/幂等/派发/FINAL→Settlement→Ledger 与 SSE permit 不变量。首要瓶颈为 `billing_period` 行锁 + `gateway_request` 账期外键共享锁排队；双实例不提升单账期吞吐；同时修复预流失败 SSE permit 泄漏。详见 [HC-01 验收](maintenance/2026-11/2026-11-11-hc-01-gateway-concurrency/2026-11-11-hc-01-gateway-concurrency.md) 与 [findings](maintenance/2026-11/2026-11-11-hc-01-gateway-concurrency/findings.md)。合并前审查修正（SSE 建流前取消 permit 复现/修复与 HC-02 索引建议更正）见 [HC-01 review follow-up](maintenance/2026-11/2026-11-11-hc-01-gateway-concurrency/2026-11-11-hc-01-review-followup.md)。
- **HC-03 Gateway 双实例故障接管、依赖中断与财务恢复（Issue #180）**：独占 MySQL 8.4 / Redis 容器 + 两个独立 Gateway JVM + Backend 结算 Worker；真实注入 Redis 停服/黑洞、MySQL 停机、Gateway `taskkill /F`（确定未派发窗口 / TX2 后窗口）、A 崩溃 B 接管、结算事务中断与数据库暂断、真实 HTTP Close 并发。逐次记录注入/发现/恢复/健康时间与请求结果，验证预算不超扣、同键不双派发、UNKNOWN/INCOMPLETE 不伪造 FINAL、PENDING_HOLD 不误释放、FINAL→Settlement→Ledger 1:1、Close blocker 看见已提交派发事实。详见 [HC-03 验收](maintenance/2026-11/2026-11-11-hc-03-failure-recovery/summary.md)、[运行说明](maintenance/2026-11/2026-11-11-hc-03-failure-recovery/runbook.md) 与 [故障矩阵](maintenance/2026-11/2026-11-11-hc-03-failure-recovery/fault-matrix.md)；PR #181 审查后的 Trivy/CI 恢复与 harness 资源所有权加固见 [review follow-up](maintenance/2026-11/2026-11-11-hc-03-failure-recovery/review-followup.md)。
- **HC-02 账期热点锁竞争最小优化（Issue #178）**：先只读审计 TX1/TX2/Close/Settlement 锁图，证明账期 X 锁同时承担“RR 读视图串行化”的隐性正确性职责；随后 TX1/TX2 改共享账期锁 + `READ COMMITTED` 当前读，usage finalization 的 `lockLineage` 收敛为 `FOR UPDATE OF gr, ra`（不锁共享 `pricing_version` 行），预算行 X/Close X/财务语义/全部表与索引不变。A/B 8 个场景 3 轮：RPS +14%～+206%、P95 −22%～−70%，账期锁等待 254.8 ms→1.96 ms，财务不变量 0 违例、结算完全收敛。详见 [HC-02 验收](maintenance/2026-11/2026-11-11-hc-02-billing-period-lock/2026-11-11-hc-02-billing-period-lock.md)、[锁审计](maintenance/2026-11/2026-11-11-hc-02-billing-period-lock/lock-boundary-audit.md) 与 [A/B 对比](maintenance/2026-11/2026-11-11-hc-02-billing-period-lock/comparison.md)。
- **深度代码审查（`75f02ea`，2026-11-12）**：只读精读约 70 个文件 / 约 1.1 万行（Gateway 请求/预占/派发/计量/结算/恢复、Backend 结算/账本/预算/关账/配置校验、前端金额与认证层、CI 与 compose）；复核 F-01~F-09 全部成立，本地 1516 个单测/架构/前端用例全绿、文档 0 死链；记录 4 项 P1（生产默认密钥校验、CI 集成测试可空、CI 门禁可被 skipped 绕过、登出失败不清会话）+ 22 项 P2 + 6 项 P3，本次**未改任何代码**：[深度代码审查记录](maintenance/2026-11/2026-11-12-deep-code-review.md)。
- **P1 外围防线加固与 P2 口径修复（2026-11-12）**：生产配置校验器拒绝仓库公开的默认 JWT / 对象存储凭据；CI 集成测试选择抽出脚本并对空枚举/空分片非 0 退出（当时 199 个 `*IntegrationTest.java`，现为 201 个，分片 101/100）；`ci-status` 对无理由 skipped 判红并新增 `CODEOWNERS`（branch protection / ruleset 仍 `NOT_VERIFIED`）；登出失败也清理本地会话；Gateway 流式成功补记 `COMPLETED`、`SAFE_RELEASED_TERMINAL` 不再落 `UNKNOWN`；三处列表改按数值 id 倒序。逐项 before/after、验证输出与 `NOT_VERIFIED` 见 [P1 加固修复记录](maintenance/2026-11/2026-11-12-p1-hardening-fixes.md)。
- **CI push 比较基准与前端测试定时器清理（2026-11-12）**：`dorny/paths-filter` 显式 `base: ${{ github.ref }}`，dev push 不再相对 main 全量运行；Vitest 全局 `afterEach` 增加 20ms 收敛窗口，修复 antd `useDelayState` 定时器在 jsdom teardown 后触发导致的 `frontend-test` 未处理错误：[CI 比较基准与测试 teardown](maintenance/2026-11/2026-11-12-ci-push-base-and-frontend-timer-teardown.md)。
- **剩余 P2/P3 低风险清理（批次 A，2026-11-12）**：dev Compose 的 MySQL/Redis/MinIO 端口收敛到 `127.0.0.1`；HC-01 Redis 容器删除前校验 owner label（无 label / 他人 owner 拒绝并给出人工命令）；CodeQL 覆盖 `scripts/`；前端显式声明 `dayjs` 并移除零引用 `echarts`；修复 `percentOf` 正则笔误；分摊编辑器非法金额降级为字段级“金额格式异常”；删除 `AppPage` 死代码与 `WORKBENCH_SECTION` 幽灵权限码，并新增导航权限目录一致性测试：[剩余 P2/P3 低风险清理](maintenance/2026-11/2026-11-12-remaining-p2-p3-cleanup.md)。
- **剩余 P2/P3 后端修复（批次 B，2026-11-12）**：Gateway 抽公共授权前置段并把 `authorizeAndFence` 显式标注为仅测试入口；成功终结的 `TRANSPORT_COMPLETED` 谓词接受 `DISPATCH_INTENT`，消除 0 行更新回滚；定价创建重复维度返回 400、organization 行锁串行化版本分配、唯一键冲突返回 409；路由策略列表用一次批量查询消除 N+1；对账僵尸 `RUNNING` run 由启动 + 定时扫描收敛为 `FAILED` 并写审计；删除 `EXPENSE_RESUBMIT` 与 `InitialCandidateResult.skipped()` 死代码：[剩余 P2/P3 后端修复](maintenance/2026-11/2026-11-12-remaining-p2-p3-backend.md)。
- **剩余 P2/P3 决策项落地（批次 C，2026-11-12）**：Gateway 调度池 2 线程 + Advisor 批量上限/开关；生产配置本地性改为 URI/InetAddress 解析、存储内网明文需显式开关；登录未知邮箱执行 dummy bcrypt；异常基线与 Workbench 统一观测/收费基；定价 `unitPrice` 响应改 decimal string、补齐 14 条 OpenAPI path/19 operation/21 schema 并新增路由覆盖测试；Provider 传输层 SSRF 与内置主机白名单默认开启；技术时间统一 UTC；账期客户端/缓存键合并；证据下载延迟 revoke；`CACHED_INPUT_TOKEN` 配置返回 400：[剩余 P2/P3 决策项落地](maintenance/2026-11/2026-11-12-remaining-p2-p3-decisions.md)。
- **运行期专项验收（2026-11-13）**：真实 Docker HC-01 owner label 归属（无 label / 他人 owner 拒绝、本 run 允许）；Edge 验证 `/ledger` 与 Provider 连接 UTC、证据下载 200 B 且 SHA-256 匹配；对账 31 分钟僵尸 RUNNING 经真实 `@Scheduled` 收敛为 `FAILED/RECONCILIATION_RUN_ABANDONED` 并写审计；发现并修复 `prod` profile 因 `ProductionConfigurationValidator` 构造器装配失败无法启动（`@Autowired`），以及微小定价费率响应 `1E-8` 导致页面 `—`（scale-8 serializer）；修复后 prod 8/8、定价 API/Edge PASS。可选 HC-01 quick 未运行，Firefox/Safari `NOT_VERIFIED`：[运行期专项验收](maintenance/2026-11/2026-11-13-runtime-verification.md)。
- **HC-01 quick 冒烟（2026-11-12）**：在 `dev=046c4f3` 上重建 Backend/Gateway JAR 后运行完整 quick 矩阵（13 场景，5–20 并发档位）；Gateway loopback Mock Provider 仅在 SSRF private-endpoint opt-in 下可达，resolver 测试 13/13 证明默认/prod 拒绝；真实 Docker 探针验证 Redis owner label 不误删无 label/他人/HC-03 容器；8 个组织 2153 条 FINAL 全部 1:1 结算入账，20 个预算 0 超支，0 duplicate FINAL / 0 缺失 posting；Docker VM 1.86 GiB，耗时约 4 分 26 秒，清理后无 Redis 容器残留：[HC-01 quick 冒烟验收](maintenance/2026-11/2026-11-12-hc-01-quick-smoke.md)。

OpenCode Zen / Kilo Gateway 已于 2026-11 退役，相关接入与协议维护记录作为历史证据保留在 [`maintenance/2026-10/`](maintenance/2026-10/)；Retirement 决策与验证见 [共享库 Provider 退役记录](maintenance/2026-11/2026-11-14-shared-library-provider-retirement.md)。Cost Intelligence 历史覆盖与输入身份改进、结算 Worker 可靠性修复等变更的逐项记录见 [`maintenance/2026-10/`](maintenance/2026-10/)。

M9 总入口：[`milestones/m9-acceptance-matrix.md`](milestones/m9-acceptance-matrix.md)；最终收敛报告：[`milestones/aic-083-m9-final-acceptance.md`](milestones/aic-083-m9-final-acceptance.md)；逐项证据见 [`milestones/`](milestones/)。

## 目录分层与指引

| 子目录 | 内容与职责 |
|---|---|
| [`checklists/`](checklists/) | 验收基准清单：[01-PR验收清单](checklists/01-PR验收清单.md)、[02-Milestone验收证据矩阵](checklists/02-Milestone验收证据矩阵.md) |
| [`milestones/`](milestones/README.md) | 历史正式里程碑与版本封存证据：M9 ~ M22 验收矩阵、AIC-073/083、V1 RC 报告等；完整索引见 [`milestones/README.md`](milestones/README.md) |
| [`maintenance/`](maintenance/) | 维护期 Defect 修复、安全加固与专项审查报告（按 `YYYY-MM/` 归档） |
| [`quality/`](quality/) | 质量控制与测试验证策略（`12-test-and-validation.md`） |
| [`implementation/`](implementation/) | 历史实现阶段证据与风险登记表 |

## 日常 PR 验收

先看：

```text
checklists/01-PR验收清单.md
quality/12-test-and-validation.md
```

## Milestone 验收

看：

```text
checklists/02-Milestone验收证据矩阵.md
quality/12-test-and-validation.md
```

M0 Foundation 的本地实现证据：

```text
implementation/08-m0-foundation-evidence.md
```

## v1.0.0 最终验收

看：

```text
implementation/06-v1-release-acceptance.md
checklists/02-Milestone验收证据矩阵.md
implementation/07-risk-register.md
milestones/v1-release-candidate-evidence.md
milestones/aic-073-final-human-acceptance.md
```

## 重要原则

不能用：

```text
“我本地跑过”
“页面看起来没问题”
“AI 说测试通过”
```

替代真实证据。

需要按任务保存：

```text
CI Result
Test Output
Integration Test
Concurrency Test
EXPLAIN
Benchmark Report
Compose Smoke
Known Limitations
Human UAT / Release Sign-off
```

性能数字只能在真实 Benchmark 后写入 README / 简历。

## V1 Final Acceptance

AIC-071 + AIC-072 的 Release Candidate 证据见：

```text
../superpowers/specs/2026-08-23-m8-compose-smoke.md
milestones/v1-release-candidate-evidence.md
```

`milestones/v1-release-candidate-evidence.md` 的 Final boundary 记录的是 AIC-072 RC 冻结时点的历史状态；其中 `AIC-073: FROZEN / NOT EXECUTED` 与 `Human acceptance: PENDING` 已由后续 AIC-073 最终签署记录 supersede，不应再解释为当前状态。

AIC-073 Final Human Acceptance / Release Sign-off 已完成，最终记录见：

```text
milestones/aic-073-final-human-acceptance.md
```

最终结论：

```text
AIC-073 = COMPLETED
Human acceptance = ACCEPTED
P0/P1 = 0
Release blocker = NONE
RELEASE_READY = YES
```

## Release Outcome

AIC-073 sign-off 后已完成正式发布：

```text
v1.0.0
→ 982d06a0e9ec844ea687ed746d6b9d8f39d86686
→ Published
```

发布后，main push CI 暴露出 Import lease recovery 在 cancel/recover race 下的 transient MySQL deadlock flake。PR #103 以 bounded deadlock retry 做最小加固，并在 Java 21 GitHub Actions 上通过 7/7 CI；合并后的 main push CI 也成功。随后发布：

```text
v1.0.1
→ b96be614e2d843c101add49fe6daffb9d2343a56
→ Published
```

`v1.0.0` tag 保持固定，不因后续补丁移动。`v1.0.1` 不改变 API、Schema 或 V1 产品范围。

## Frozen Evidence Policy

以下材料是 V1 的时间点历史证据，应保留其当时状态，不为了“看起来最新”而改写：

```text
v1-release-candidate-evidence.md
AIC-071 / AIC-072 RC evidence
AIC-073 final sign-off baseline
M0–M8 milestone evidence
V1 benchmark reports
```

当前入口文档负责说明“现在是什么状态”；历史证据负责说明“当时如何证明”。

