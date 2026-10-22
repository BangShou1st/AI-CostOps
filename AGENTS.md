# AGENTS.md — AI CostOps

面向 AI 编码代理的项目上下文。**每个新会话开始时先读本文件**，再读 [`PROJECT_CONTEXT.md`](PROJECT_CONTEXT.md)（项目状态权威索引）与 [`CONTRIBUTING.md`](CONTRIBUTING.md)（协作规范）。

---

## 1. 这是什么项目

AI CostOps 是面向研发团队的 **AI 成本治理（FinOps for AI）** 平台：把多 Provider 已发生的 AI 消费收敛成一本可信、可归属、可审批、可对账、可关账的内部成本账。

- **仓库**：`E:\project\AI-CostOps`
- **远程**：`https://github.com/BangShou1st/AI-CostOps.git`
- **当前阶段**：`FINAL_PRODUCT_VERSION = V3` / `STABLE_RELEASE = v3.0.0` / `Feature development = CLOSED` / `Operational mode = MAINTENANCE_ONLY` / `V4 = NOT_PLANNED`
- **许可**：专有，内部使用

### 当前阶段最重要的一条约束

> **功能研发已关闭。** 不要新增 Provider、业务功能、V4 能力或"顺手重构"。
> 只接受：安全更新、缺陷修复、运维修复、CI / 依赖维护、文档维护。
> 维护的触发条件是**真实缺陷与实际必要性**，不是"看起来可以优化"。

---

## 2. 仓库结构

```text
AI-CostOps/
├── backend/      Java 21 · Spring Boot 4.1 · Spring MVC · 控制面（Financial Truth Owner）
│   └── src/main/java/com/aicostops/    22 个业务模块 + shared
├── gateway/      Java 21 · Spring Boot 4.1 · Spring WebFlux · 数据面（请求事实写入方）
├── frontend/     React 19 · TypeScript 6 · Vite 8 · Ant Design 6 · TanStack Query 5 · ECharts 6
├── scripts/      Node ESM 任务编排器（零依赖、跨平台）→ 根 package.json 的唯一入口
├── deploy/       生产 / 可观测性编排
└── docs/         01-blueprint · 02-development · 03-acceptance · 04-operations
```

### 模块职责边界（不可越界）

| 区域 | 负责 | 不负责 |
|---|---|---|
| Backend（控制面） | IAM、组织、预算、账本、对账、关账、审计、报表、**最终结算与账本写入** | 不代理 Provider 请求 |
| Gateway（数据面） | Provider 请求代理、身份识别、预算预占、实时计量、路由、用量事实写入 | **不写最终财务事实** |
| Frontend | 管理台 UI | 不做业务判定 |

---

## 3. 不可违反的不变量

这些是财务系统的正确性边界。**修改任何相关代码前先确认没有破坏它们。**

```text
one monorepo / two deployables / one final financial truth

MySQL  = 身份与最终财务真相（预算预占正确性、最终结算、账本）
Redis  ≠ 财务真相，不能独立授权任何金额支出
Gateway = 请求 / 路由 / 用量事实写入方
Core   = Settlement / Ledger / Budget Actual / Commitment 写入方

DISPATCH_INTENT 必须先持久化，再发生可能计费的 Provider I/O
missing usage ≠ zero cost        （拿不到用量绝不按 0 计费）
POSTED Ledger 不可变             （更正只能追加 Correction 流水）
CLOSED 账期写保护                （重开需显式动作 + 审计）
Budget available = total - actual - outstanding commitments
已发生的 Provider Cost 不因预算不足而丢弃
```

### 模块依赖方向

Backend 内部按 `ModuleDependencyArchitectureTest` 约束。跨模块只能通过 application 层接口，**禁止直接访问别的模块的 repository / mapper**。

---

## 4. 环境与命令

### 前置

```text
Node.js  ≥ 24.14.0
JDK      ≥ 21
Docker   任意近期版本（本地跑 MySQL / Redis / MinIO）
```

### 日常开发模式（重要）

> **日常 edit-test 循环：Docker 只跑基础设施，Backend / Gateway / Frontend 三个进程本机原生运行。**
> 禁止在日常循环里反复 `docker compose build` / `docker compose up --build` / `docker build *`。

```bash
cp .env.example .env          # Windows: Copy-Item .env.example .env
npm run setup                 # 首次或依赖变更后（在 frontend 执行 npm ci）
npm run doctor                # 检查 Node/Java/npm/Docker/Maven/端口
npm run infra:up              # MySQL / Redis / MinIO
npm run dev                   # 前台启动三端，Ctrl+C 停止
```

| 端口 | 服务 |
|---|---|
| 5173 | Frontend（Vite） |
| 8080 | Backend |
| 8081 | Gateway |
| 3307 | MySQL |
| 6379 | Redis |
| 9000 / 9001 | MinIO API / Console |

### 测试命令

```bash
# 单元
cd backend && ./mvnw -B -DexcludedGroups=architecture,integration test
# 架构（ArchUnit）
cd backend && ./mvnw -B -Dgroups=architecture test
# 集成（Testcontainers：真实 MySQL 8.4 + Redis，不用 H2）
cd backend && ./mvnw -B verify
# 前端
cd frontend && npm run lint && npm test -- --run && npm run build
# E2E
cd frontend && npm run test:e2e
# 脚本
npm run test:scripts
```

**集成测试很慢**（CI 约 8 分钟，本机更久），且需要 Docker。改完代码优先跑单元 + 定向回归，最后再跑完整 verify。

### PowerShell 注意事项

```powershell
# Maven 的 -D 参数必须整体加引号，否则 PowerShell 会把它拆坏
.\mvnw.cmd -B "-Dit.test=SomeIntegrationTest" "-DfailIfNoSpecifiedTests=false" verify
```

---

## 5. 测试约定（改测试前必读）

### 共享 MySQL Testcontainer

所有集成测试跑在**同一个** MySQL Testcontainer 里（`MySqlContainerSupport.MYSQL` 是 static 字段）。因此：

> **测试之间会互相看到对方留下的数据。** 清理不完整会直接变成下一个测试的 FK 约束失败。

### `app_user` 的 5 个外键子表

删除 `app_user` 之前必须先清掉这 5 张表，否则报 `DataIntegrityViolationException`：

```text
user_credential        (fk_user_credential_user)
organization_member    (fk_organization_member_user)
invitation             (fk_invitation_accepted_user)
audit_event            (fk_audit_event_actor)
cost_history_coverage  (fk_cost_coverage_actor, V28 新增)
```

### 正确做法：复用 `M2DatabaseCleaner`

```java
com.aicostops.testsupport.M2DatabaseCleaner.clean(jdbc);
```

它是**一趟 FK-safe 的全量清理**（child-before-parent），仓库里 170+ 个测试类都用它。**不要**自己手写局部的 `DELETE FROM app_user` —— 那正是 2026-10-21 CI 失败（14 个 FK 错误）的根因。

如果测试需要保留 seed 的 `role` / `permission` / `role_permission` 目录，只额外删自己创建的 role：

```java
M2DatabaseCleaner.clean(jdbc);
jdbc.update("DELETE rp FROM role_permission rp JOIN `role` r ON r.id=rp.role_id WHERE r.code=?", code);
jdbc.update("DELETE FROM `role` WHERE code=?", code);
```

### 自己建夹具的测试必须自己清理

`@BeforeEach` 建了数据就要在 `@AfterEach` 清掉。典型反例：`DevAuthenticationBootstrapIntegrationTest` / `LocalProfileDevBehaviorIntegrationTest` 建了 bootstrap 管理员（`app_user` + `user_credential`）却不清，后面的测试 `DELETE FROM app_user` 就被 FK 卡住。

---

## 6. 数据库变更

- 所有 Schema 变更走 **Flyway**，迁移文件放 `backend/src/main/resources/db/migration/V<n>__<name>.sql`
- **已发布的迁移不可修改**——改历史迁移会导致已部署环境的 checksum 不匹配
- 新迁移编号必须递增，不复用
- 财务相关表加外键前先确认删除顺序不会破坏 `M2DatabaseCleaner` 的 child-before-parent 假设
- 涉及金额的列用 `DECIMAL`，不用 `FLOAT` / `DOUBLE`
- 金额比较用 `BigDecimal.compareTo`，不用 `equals`

---

## 7. API 变更

任何 API 变更必须**同时**更新：

```text
docs/02-development/api/openapi.yaml      Control Plane 契约
docs/02-development/api/gateway-openapi.yaml   Gateway 契约
Controller / DTO                          后端实现
frontend/src/api + types                  前端客户端与类型
Contract / Integration Test               契约测试
```

**禁止口头修改接口。**

---

## 8. 安全红线

```text
禁止提交 .env / 真实密码 / JWT Signing Key / Provider API Key
禁止提交真实 Provider 原始导出 / 本地数据库数据 / 日志 / Build Output
禁止在日志里打印 password / token / secret / apiKey / credential
禁止 blanket 忽略安全扫描结果
```

- 依赖漏洞 `HIGH` / `CRITICAL` **直接阻断**，不做 blanket ignore
- `.trivyignore` 只在存在真实、已评审、限期的 finding 时逐项记录；当前为 `Accepted risks: NONE`
- 只有 CodeQL 作业拥有 `security-events: write`，workflow 默认 `contents: read`
- 生产 profile 下配置校验器会拒绝默认回退密钥、localhost Origin、开发引导开关

Provider 测试夹具只允许：`REAL_SCHEMA_SANITIZED` / `OFFICIAL_SCHEMA_SYNTHETIC` / `SYNTHETIC_ENTERPRISE`。

---

## 9. Git 流程

```text
Issue → 短生命周期分支 → Pull Request → CI → 人工验收 → Squash Merge → main
```

```text
分支前缀：feat/*  fix/*  refactor/*  test/*  perf/*  docs/*  chore/*
```

**禁止直接在 `main` 上开发。**

提交信息用 Conventional Commits：

```text
feat(iam): implement password login
fix(import): recover expired worker lease
test(budget): cover concurrent activation
docs(api): update budget contract
chore(ci): add backend workflow
```

禁止：`update` / `final` / `fix` / `111` / `改一下`

### 分支同步策略

- `main` = 稳定发布线；`dev` = 维护进行线
- 修复先落 `dev`，CI 与 Security 全绿后再同步到 `main`
- 同步时保持 `main` 与 `dev` 指向同一提交（或保持 `main` 为 `dev` 的祖先），**不留分叉**
- 合并后本地与远程必须一致：`git fetch --all --prune` 后确认 `git status` 干净

---

## 10. 文档约定

| 目录 | 放什么 |
|---|---|
| `docs/01-blueprint/` | 产品范围、领域模型、业务规则、架构、ADR、路线图 |
| `docs/02-development/` | 详细设计、实现记录、API 契约、运维手册 |
| `docs/03-acceptance/` | 里程碑验收证据矩阵、逐次验收记录 |
| `docs/04-operations/` | 生产运维 Runbook |

**规则：**

- 历史时间点的结论**不得**改写成当前状态（冻结证据是资产）
- 新增文档后要在对应目录的 `README.md` 索引里登记
- README 的链接必须指向**真实存在**的路径——写完逐个验证
- 变更记录写进 `CHANGELOG.md` 的 `[Unreleased]` 段

---

## 11. 给 AI 代理的额外约束

1. **先读后写**：改任何文件前先读它。不要凭猜测编辑。
2. **改代码前先说清楚为什么**：修 bug 要给出根因，不要打补丁掩盖症状。
3. **不要扩大改动范围**：修 CI 红了就只修 CI 红的原因，不要顺手重构无关模块。
4. **不要为了让测试通过而删断言或加 `@Disabled`**——那是掩盖问题，不是修复。
5. **跑不通就报告，不要假装通过**。CI 状态以 GitHub Actions 实际结果为准。
6. **提交前自检**：
   ```bash
   git status              # 只应有预期的改动
   git diff --stat         # 确认没夹带无关文件
   ```
7. **绝不提交**：`.env`、`.local-dev/`、`target/`、`node_modules/`、`test-results/`、日志、真实 Provider 数据。
8. **构建产物隔离**：根任务命令的 Maven 输出在 `.local-dev/builds/`，不覆盖正在运行的 JAR。
9. **中文回复**（除非用户明确要求其他语言）。

---

## 12. 常用入口

| 需求 | 位置 |
|---|---|
| 项目状态 / 里程碑 | [`PROJECT_CONTEXT.md`](PROJECT_CONTEXT.md) |
| 协作与提交规范 | [`CONTRIBUTING.md`](CONTRIBUTING.md) |
| 变更记录 | [`CHANGELOG.md`](CHANGELOG.md) |
| 脚本职责 | [`scripts/README.md`](scripts/README.md) |
| 产品范围 | [`docs/01-blueprint/product/05-product-scope.md`](docs/01-blueprint/product/05-product-scope.md) |
| 架构决策 | [`docs/01-blueprint/architecture/adr/`](docs/01-blueprint/architecture/adr/) |
| Control Plane API | [`docs/02-development/api/openapi.yaml`](docs/02-development/api/openapi.yaml) |
| Gateway API | [`docs/02-development/api/gateway-openapi.yaml`](docs/02-development/api/gateway-openapi.yaml) |
| 生产运维 | [`docs/04-operations/v3-operational-runbook.md`](docs/04-operations/v3-operational-runbook.md) |
| 验收证据 | [`docs/03-acceptance/`](docs/03-acceptance/) |
