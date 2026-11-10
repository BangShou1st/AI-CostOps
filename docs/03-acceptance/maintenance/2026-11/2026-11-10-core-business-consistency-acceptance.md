# 核心业务真实性与产品一致性验收（2026-11-10）

> 关联设计：[`docs/02-development/designs/maintenance/2026-11-10-backend-reliability-refactor-design.md`](../../../02-development/designs/maintenance/2026-11-10-backend-reliability-refactor-design.md)
> 分支基线：`dev`（`bc1ead9` 起，验收后合并至 `dev`，见文末提交清单）
> 执行环境：本机 Windows / Docker Desktop（Testcontainers 真实 MySQL 8.4 + Redis 7）/ 本地全栈（Frontend 5173 + Backend 8080 + Gateway 8081）
> 文档性质：**验收证据**。结论标记：`PASS` / `FAIL` / `NOT_VERIFIED` / `BLOCKED`

## 0. 结论摘要

| 验收域 | 结论 |
|---|---|
| 业务链路 A：AI Gateway 实时成本治理 | **PASS**（8/8 检查点，156 用例 0 失败，无业务缺陷） |
| 业务链路 B：外部账单归集与财务对账 | **PASS**（8/8 检查点，277+ 用例 0 失败，无业务缺陷） |
| 产品一致性（前端-后端状态/术语/声明） | **PASS（附遗留清单）**——未发现 P0 业务正确性问题；修复了 1 处能力声明失真、1 处契约优先级歧义、前端"说明书式"文案与枚举暴露 |
| 前端截图验收（浏览器实测） | **PASS（附遗留清单）**——无崩溃/白屏/布局崩坏；枚举直出与旧文案残留已在补充修复中清理 |

明细见对应章节与两份链路验收记录：

- 链路 A：[`2026-11-10-gateway-chain-e2e-acceptance.md`](2026-11-10-gateway-chain-e2e-acceptance.md)
- 链路 B：[`2026-11-10-billing-chain-e2e-acceptance.md`](2026-11-10-billing-chain-e2e-acceptance.md)

## 1. 业务链路 A：AI Gateway 实时成本治理

8 个检查点全部 `PASS`：请求前预算校验与预占（TX1/TX2 先于 Provider I/O）、并发不突破预算（8 路竞争恰好 `sumHolds ≤ 100`）、超时/断流处理（UNKNOWN + `PENDING_HOLD`，不丢不释放）、missing usage ≠ zero cost（结算仅取 `FINAL`）、幂等不重复扣费（双 settle 仅 1 行账）、DB 写失败整体回滚（故障注入 5 埋点）、Redis 不可用 fail-closed（预算零 Redis 依赖）、前端数据契约可追溯。**无缺陷，无生产代码变更。**

运行规模：gateway 66 + 16 + 18 用例、backend 56 用例，全部真实 Testcontainers MySQL/Redis，0 失败。

## 2. 业务链路 B：外部账单归集与财务对账

8 个检查点全部 `PASS`：file_hash 层防重传（唯一约束 + 复用语义）、跨文件记录指纹去重（duplicate review）、金额 DECIMAL(20,8) + 币种 CHECK 约束（一次性全量迁移实测）、Worker 租约/`SKIP LOCKED`/fencing 崩溃恢复、Ledger 不可变架构测试 + Correction 只追加、CLOSED 账期 8 项 blocker + 全写入口财务栅栏、对账差异溯源到 `provider_request_id` 级、前端报表契约一致（含 M9 报表基准 381s 全绿）。**无 P0/P1 缺陷**；唯一更正是过时测试方法名（`...Seven...` → `...Eight...`，断言未动）。

## 3. 产品一致性审查结果

### 3.1 后端审计（只读全量扫描）

- 主代码 737 个 Java 文件：**无零引用死类、无 TODO/FIXME/占位实现**；前端 80+ API 路径全部真实存在；前后端状态枚举抽查 8 组全部一致；README 能力声明逐项核到代码 + 测试证据（详见审计记录，无"声明了但缺证据"项）。
- 环节一致性与截图阶段发现的 404（`/cost-intelligence/budget-risks`、`/ai-advisor/profile`）核实为**接口存在、"资源未配置"语义**，前端已优雅降级——非缺陷。

### 3.2 已修复的一致性问题

| 问题 | 修复 |
|---|---|
| README「七项强一致校验」与代码 8 项 `CloseBlockerCode` 不符 | 更正为八项 |
| 三份 OpenAPI spec 优先级歧义（V3 智能面未回填） | `docs/02-development/api/README.md` 明确冻结面/增量/DTO 权威口径 |
| 前端 4 个 API 文件头"strictly mirror openapi.yaml"声明失真 | 改为实现契约对齐说明 |
| `deploy/production/README.md` 版本标签停留在 M9/v1.1 | 更新为 V3/v3.0.0 · MAINTENANCE_ONLY |
| 前端"说明书式"教学文案、内部术语（英文枚举/权限码/M2·M13 里程碑编号/租约/BigDecimal）暴露 | 50 文件文案改造 + 31 文件枚举翻译与残留清理（详见 §4） |

### 3.3 不在本轮范围的建议（低优先级，未改动）

- `openapi.yaml` 尚未回填 V3 成本智能 / AI Advisor / 预算承诺 / 路由策略面（已声明口径，回填属契约维护工作）。
- 手输 ID 表单（预算范围 ID、更正目标 ID、路由策略 4 个 ID 等）可后续补选择器。
- 原生 `<select>/<input>` 与 antd 组件混用（分摊行编辑器、币种输入、日期输入）属可用性批次。
- 侧边栏 26 项全平铺，设置区可加分组标签。
- 无 OpenAPI → 前端类型的自动漂移检测，依赖人眼。

## 4. 前端文案改造与截图验收

### 4.1 改造范围（两轮）

第一轮（`fix/frontend-copy-consistency`）：50 文件——对账四弹窗、智能四页、Advisor、Provider 三页的"说明书式"文案压缩；权限码、里程碑编号、内部契约语言清理；Alert `message=` → `title=` 统一（antd 6.6 已确认 `message` 废弃）；新增 `formatBytes`。e2e m15/m19 断言同步。

第二轮（`fix/frontend-copy-residuals`）：31 文件——截图验收发现的英文枚举直出（运行状态/算法枚举/对象类型/分配目标/连接协议认证/定价维度/审核状态）建立 feature 级中文映射；「探针」「Top drivers」「暂无 attempt」「服务端」等残留清理；账本金额复用 `formatMoney` 去尾零；antd 6 弃用 prop（`maskClosable`、Drawer `width`）机械迁移。

### 4.2 验证

- `npm run lint` / `npm test -- --run`（547/547）/ `npm run build` 全绿；`npm run verify:links` 0 死链。
- **浏览器截图验收**（browser-skill 驱动真实 Edge，本地全栈）：登录 + 22 个页面首轮 20 张截图、修复后 13 张 `-v2` 复验截图（`.local-dev/screenshots/2026-11-10/`，不入库）。无 React 崩溃/白屏/布局崩坏；高风险点（模型定价常驻 Alert `title` 渲染、报销「可入账」列头、对账新文案）确认通过。
- MiMo 流式集成测试在适配器去重后复跑 `MimoStreamingIntegrationTest`：9/9 通过（`BUILD SUCCESS`）。

### 4.3 E2E 回归与发现

- `m19-cost-intelligence-overview.spec.ts`（新四问文案断言）：**PASS**。
- `m15-hybrid-reconciliation.spec.ts` 首轮失败，根因**与文案改造无关**：分配目标下拉为 antd 虚拟列表且目录按 id 升序，复用的本地数据库累计 45 个项目使新建测试项目位于虚拟滚动窗口外（CI 全新库不触发）。修复为输入搜索词过滤后再选项（测试健壮性修复，未弱化断言）。
- 过程中验证了登录限流器的真实 fail-closed 行为（重复登录触发 `AUTH_RATE_LIMITED` 429，Redis 键 TTL 正常递减）。

### 4.4 遗留（NOT_VERIFIED / 低优先级）

- 「提交整体调整」弹窗的浏览器实开复验：库中无 INVESTIGATING 状态案例，按钮正确禁用，未做数据篡改；分配目标「项目」映射由单元测试覆盖。
- 「供应商账号 : 34」显示原始 ID：API 载荷无名称字段，不编造数据，保持原样（属后续可用性批次）。
- 旧 M6 算法枚举（`M6_PERIOD_PROVIDER_CURRENCY_V1`）未映射，按"未知值回退原文"原则显示原文。

## 5. 代码清理（审计驱动，零功能损失）

| 项 | 类型 | 依据 |
|---|---|---|
| `aicostops.gateway.port` 死配置（getter 零消费者，与 `server.port` 冗余映射同一环境变量） | DELETE | gateway 单测 207 全绿 |
| `deploy/production/m16-gateway-grants.sql`（全仓 0 引用，内容停留 V23 schema，已被 `scripts/m21/provision-gateway-db.ps1` 取代） | DELETE | 全仓 grep |
| `MimoSseDecoder` / `MimoWireDtos` 与 OpenAI 侧逐字重复 | SIMPLIFY | 适配器本体不动；去重后流式集成测试 9/9 通过 |

## 6. 提交清单（dev）

```text
bc1ead9 docs(design): persist backend reliability refactor design (CO-00~CO-05)
bca10d5 merge: frontend copy rewrite and Alert API unification
4aa6500 merge: backend dead config removal, MiMo dedup and doc accuracy fixes
313da77 merge: chain A gateway real-time cost governance e2e acceptance evidence
6161572 merge: chain B billing ingestion and reconciliation e2e acceptance evidence
ebd8c05 fix(frontend): translate raw enums and remove residual manual-style copy
1610051 merge: translate raw enums, remove residual server-speak copy and antd deprecations
<待补> test(e2e): m15 allocation target search robustness fix
```

## 7. 总体结论

**核心业务真实性：PASS。** 两条核心链路的全部 16 个检查点均有真实 Testcontainers 自动化覆盖并复跑通过，未发现任何违反财务不变量的缺陷；本轮全部变更（文案/枚举/文档/死代码删除/测试健壮性）均为零财务语义改动。**NOT_VERIFIED 项**：真实 Provider 端到端（沿用 2026-10-22 真实上游验收先例，本轮未重跑）；全量 `./mvnw verify`（CI 承担）。**BLOCKED 项：无。**
