# 2026-11-12 剩余 P2/P3 低风险清理（批次 A）

| 项目 | 内容 |
|---|---|
| 日期 | 2026-11-12 |
| 责任人 | AI（DeepSeek Harness；中心 agent + 3 个并行子代理） |
| 关联模块 | dev Compose、HC-01 性能 harness、CodeQL 配置、Frontend 依赖 / 分摊编辑器 / 导航权限 |
| 缺陷来源 | [2026-11-12 深度代码审查](2026-11-12-deep-code-review.md) P2-03 / P2-04 / P2-05 / P2-19 / P3-02 / P3-05 / P3-06 |
| 代码基线 | `dev` = `278e27a`（`main` = `39560cc`） |
| 关联分支 | `fix/a-infra-deps`、`fix/a-hc01-ownership`、`fix/a-frontend-cleanup` → 集成 `fix/remaining-a-cleanup` |
| 关联 PR | [#184](https://github.com/BangShou1st/AI-CostOps/pull/184) |
| 变更性质 | 维护期既有缺陷最小修复；无新功能、无 Flyway、无 API 契约、无财务语义变更、无新增运行时依赖 |
| 总体判定 | **PASS（本地 + 远程 CI / Security）**；仅 P2-04 的真实 Docker 容器生命周期为 `NOT_VERIFIED` |

本批只处理审查报告 §3.2 / §3.3 中列出的 7 项低风险发现，未触碰 §9 已 `FIXED` 的 6 项，也未开始批次 B/C。

---

## 1. 变更概述（before → after）

### P2-03 dev overlay 把 MySQL / Redis / MinIO 发布到所有网卡

- **位置**：`compose.dev.yaml`
- **Before**：`"${MYSQL_PORT}:3306"`、`"${REDIS_PORT}:6379"`、`"${MINIO_API_PORT}:9000"`、`"${MINIO_CONSOLE_PORT}:9001"` 未指定 host IP，Docker 默认绑定 `0.0.0.0`。开发者按文档 `npm run infra:up` 后，同网段任何主机可用示例口令直连财务库 / Redis / MinIO 控制台。
- **根因**：dev overlay 遗漏 host IP，而 `compose.v3-operational.yaml`、`compose.m16.yaml` 已写 `127.0.0.1:`；两套编排的端口策略不一致。
- **After**：四个端口全部改为 `127.0.0.1:<port>:<container>`，与生产 / M16 overlay 对齐。开发机本机访问行为不变。

### P2-04 hc01 性能 harness 无归属校验地删除固定名容器

- **位置**：`scripts/perf/hc01/runner.mjs`、新增 `scripts/perf/hc01/ownership.mjs`、`scripts/perf/hc03/ownership.mjs`（仅向后兼容扩展）
- **Before**：`ensureRedis()` 与 `removeRedis()` 直接 `docker rm -f ai-costops-hc01-redis`。并发两个 HC-01 时后启动者会删掉前者的 Redis，压测数据静默失真；同名用户容器也会被直接销毁。
- **根因**：固定容器名被无条件当作本 harness 所有；没有像 HC-03 那样用 owner label 证明归属。
- **After**：
  - 复用 HC-03 的容器决策：`ownerOf` / `decideContainer` 增加可选 `labelKey`、`harness` 参数，默认值保持 HC-03 行为不变（现有 13 个 HC-03 所有权测试继续通过）；
  - HC-01 创建 Redis 时打 `aicostops.hc01.owner / run / resource` 三个 label，owner 含本次 `RUN_NONCE` 与随机后缀，**每次 run 唯一**；
  - `ensureRedis()` 先 `docker inspect` 取 labels：无 label、其他 owner、仅 HC-03 label 一律拒绝删除并抛出可操作的人工删除命令；只有本 run 自己的遗留才允许先删后建；
  - `removeRedis()` 同样先校验；拒绝时保留容器、打印警告并写入 `state.notes`，不再隐式销毁；
  - `docker inspect` 把“容器存在但 Labels 为 `null`”映射为 `{}`，保持“无 label”与“容器不存在”可区分。
- **取舍**：崩溃 run 遗留的固定名容器不会再被下一次 run 自动清理，而是 fail-closed 要求人工 `docker rm -f`。这与 HC-03 的所有权语义一致，是有意的安全行为。

### P2-05 CodeQL 不分析 `scripts/`

- **位置**：`.github/codeql/codeql-config.yml`
- **Before**：`paths` 只含 `backend/src`、`frontend/src`、`gateway/src`；`scripts/**`（含 `child_process`、递归删除、Docker 控制）完全不进 SAST。
- **根因**：CodeQL 配置按“应用源码树”枚举，遗漏了实际在本机高权限执行的 Node 脚本。
- **After**：`paths` 追加 `scripts`；未新增 `paths-ignore`，未改 workflow / 权限。

### P2-19 前端依赖声明不完整 / 冗余

- **位置**：`frontend/package.json`、`frontend/package-lock.json`、`AGENTS.md`、`README.md`
- **Before**：`dayjs` 被 `ExpenseForm.tsx` 值导入、被 `RulesPage.tsx` / `rulesDateTime.ts` 类型导入，但未在 `package.json` 声明，`npm ci` 下只因 antd 传递依赖被 hoist 才可用；`echarts@6.1.0` 声明但 `frontend/src` 与 `frontend/e2e` 零引用（所有图表是手写 SVG）。
- **根因**：依赖声明与实际导入不一致；历史图表库未随实现方式变更而移除。
- **After**：
  - 声明 `"dayjs": "1.11.21"`（与 lock / 实际安装版本一致，不用 `^`，延续仓库固定版本风格）；
  - 删除 `echarts`，lock 由 npm 重新生成，连带移除只被 echarts / zrender 使用的 `zrender` 与 `tslib`；
  - `AGENTS.md` 前端技术栈去掉 `ECharts 6`；`README.md` 删除 `Apache ECharts` 技术栈行并修正 `lib/` 目录注释。
- **证据**：干净临时目录 `npm ci` 成功安装 296 个包，`dayjs 1.11.21`，`echarts` / `tslib` 均不存在；lock 中 `tslib` 引用数为 0。

### P3-02 `percentOf` 比较正则笔误

- **位置**：`frontend/src/features/intelligence/decimal.ts`
- **Before**：`.replace(/^0+(?=\\d)/, '')`，正则匹配字面反斜杠 + `d`，前导零实际不会被去掉。
- **After**：`.replace(/^0+(?=\d)/, '')`。
- **说明**：当前 `padStart(scale + 1)` 保证整数部分至少一位，且 `BigInt.toString()` 不产生前导零，因此该笔误**没有可观测行为差异**，无法构造“修复前失败、修复后通过”的黑盒用例；本次只修正常量表达式，原有 `percentOf` 测试保持通过。

### P3-05 分摊编辑器渲染期 `parseDecimal8` 无兜底

- **位置**：`frontend/src/features/allocation/shared/AllocationLinesEditor.tsx`、`frontend/src/features/allocation/AllocationEditor.tsx`
- **Before**：`AllocationLinesEditor` 在 render 期直接 `parseDecimal8(sourceAmount)`，`AllocationEditor` 的 `exactSum` 也直接解析 `subjectAmount`。后台若返回非 scale-8 金额，异常发生在 render → 被 `RouteErrorBoundary` 捕获成整页“页面加载失败”。
- **根因**：同文件已有 `safeParse` 只用于逐行金额，来源金额仍走抛异常路径；渲染期没有字段级兜底。
- **After**：
  - 导出 `safeParse`，两处来源金额解析都改走它；
  - `source === null` 时不再计算 `remaining`，来源金额行显示“金额格式异常”，表格与按钮照常渲染；
  - `subjectAmount` 解析失败时 `exactSum = false`、`confirmEnabled = false`，不再抛错。
- **红/绿**：新增用例先失败（`1 failed | 18 passed`，`Not an exact scale-8 money string: not-money`），实现后 `19 passed`。

### P3-06 前端死代码与“幽灵权限码”

- **位置**：`frontend/src/features/auth/AuthPages.tsx`、`frontend/src/app/layout/appNavigation.tsx`、测试与新增目录一致性测试
- **Before**：
  - `AppPage` 全仓除定义外 0 引用，自带登出按钮但不受 `ProtectedRoute` / `PermissionRoute` 约束；
  - `BUSINESS_NAV` 的工作台条目声明 `readPermission: 'WORKBENCH_SECTION'`，后端 / Flyway / 文档均无该权限码；可见性实际由 `hasWorkbenchAccess` + `WORKBENCH_SECTION_PERMISSIONS` 特判。一旦有人移除特判，工作台会对所有人隐藏且无报错。
- **After**：
  - 删除 `AppPage` 及只被它使用的 `Navigate` / `message` 导入；`useAuth` 仍被 `LoginPage` 使用，保留；
  - `AppNavEntry.readPermission` 改为可选；工作台条目不再声明幽灵码，`visibleBusinessNav` 显式分支：工作台走 `hasWorkbenchAccess`，其余条目要求真实权限码；`visibleIntelligenceNav` 同样对 `undefined` 安全处理；
  - 新增 `scripts/tests/nav-permission-catalog.test.mjs`：从全部 Flyway 迁移解析 `permission` 表种子（排除 `role_permission`），断言导航中每个 `readPermission` 都存在于 `/permissions` 的数据来源。
- **红/绿**：导航测试先失败（`1 failed | 7 passed`，工作台对象多出 `readPermission`）；目录一致性测试先失败（`WORKBENCH_SECTION` 不在 Flyway 种子）；实现后导航 `8 passed`，脚本测试 `54/54`。

---

## 2. 验证环境与执行命令

环境（2026-11-12）：Windows 11 + Git Bash / PowerShell 7，Node `v24.14.0`，npm `11.9.0`，Java `21.0.10`，Docker Desktop 引擎就绪。

| # | 命令 | 结果 |
|---|---|---|
| 1 | `npm run test:scripts` | `54 passed / 0 failed`（含既有 HC-03 所有权、CI 选择器、新增 HC-01 所有权与导航目录一致性） |
| 2 | `cd frontend; npm run lint` | 通过，无输出 |
| 3 | `cd frontend; npm test -- --run` | `73 files / 555 tests passed` |
| 4 | `cd frontend; npm run build` | 通过（`tsc -b && vite build`，`built in 475ms`） |
| 5 | `cd backend; .\mvnw.cmd -B "-DexcludedGroups=architecture,integration" test` | `663 run / 0 failed / 2 skipped`，BUILD SUCCESS |
| 6 | `cd gateway; .\mvnw.cmd -B "-DexcludedGroups=architecture,integration" test` | `211 run / 0 failed / 1 skipped`，BUILD SUCCESS |
| 7 | 临时空目录 `npm ci --ignore-scripts`（使用新 `package.json` + `package-lock.json`） | `added 296 packages`；`dayjs 1.11.21`；`echarts` / `tslib` 不存在 |
| 8 | `docker compose --env-file .env.example -f compose.yaml -f compose.dev.yaml config --format json` | `mysql 127.0.0.1:3307->3306`、`redis 127.0.0.1:6379->6379`、`minio 127.0.0.1:9000/9001` |
| 9 | `npm run verify:links` | 见 §2.1 |

### 2.1 定向红/绿证据

| 项 | 红（实现前） | 绿（实现后） |
|---|---|---|
| P2-04 HC-01 所有权 | 新测试文件 `ERR_MODULE_NOT_FOUND`（`scripts/perf/hc01/ownership.mjs` 尚不存在） | `npm run test:scripts` `54/54` |
| P3-05 非法金额 | `Tests 1 failed | 18 passed (19)`，`Error: Not an exact scale-8 money string: not-money` | `Tests 19 passed (19)` |
| P3-06 导航权限 | 前端 `1 failed | 7 passed (8)`；脚本 `WORKBENCH_SECTION` 不在种子目录 | 前端 `8 passed`；脚本 `54/54` |

### 2.2 文档链接

```text
$ npm run verify:links
[verify-doc-links] Found 248 Markdown files to inspect.
[verify-doc-links] Checked 413 links.
[verify-doc-links] SUCCESS: 0 broken links found! All local document links are valid.
```

### 2.3 远程验证（PR #184，2026-11-12）

| 工作流 | run | 结果 |
|---|---|---|
| CI | [43153454075](https://github.com/BangShou1st/AI-CostOps/actions/runs/43153454075) | 18/18 success：`detect-changes`、`backend-unit`、`backend-integration` 两片（4m13s / 4m59s）、`gateway-unit`、`gateway-integration`、`frontend-quality`、`frontend-test`（3m11s）、`scripts-unit` 两平台、`scripts-lifecycle`、`docker-build`、`browser-e2e`、`ci-status` |
| Security | [43153454086](https://github.com/BangShou1st/AI-CostOps/actions/runs/43153454086) | `trivy` + `codeql (java-kotlin)` + `codeql (javascript-typescript)` 3/3 success；JS/TS CodeQL 在写入 `scripts` 路径后正常完成（2m8s），未产生需要处置的新告警 |
| 合并 | PR [#184](https://github.com/BangShou1st/AI-CostOps/pull/184) | squash 为 `dcb0895`；仓库自动删除 head 分支 `fix/remaining-a-cleanup` |

合并后本地清理：三个子分支（`fix/a-infra-deps`、`fix/a-hc01-ownership`、`fix/a-frontend-cleanup`）、集成分支与三个临时 worktree 全部移除；本地与远程最终只保留 `dev`、`main`。

---

## 3. `NOT_VERIFIED` 与已知边界

| 项 | 状态 | 说明 |
|---|---|---|
| P2-04 真实 Docker 容器生命周期 | `NOT_VERIFIED` | 只验证了纯函数决策与 runner 语法；未启动 / 删除真实 `ai-costops-hc01-redis` 容器（会创建并销毁本机容器，且需要完整 HC-01 环境） |
| P2-05 CodeQL 对 `scripts/` 的实际分析结果 | **VERIFIED（远程）** | PR #184 的 `codeql (javascript-typescript)` 在 `paths` 包含 `scripts` 后 success（2m8s），未产生新告警 |
| 本批 PR 的远程 CI / Security | **VERIFIED（远程）** | CI 18/18、Security 3/3，见 §2.3 |
| P3-02 修复的行为差异 | 无 | 该笔误当前无可观测影响，无法提供黑盒红/绿证据 |
| 分支清理 | **VERIFIED** | head 分支由仓库自动删除；三个子分支、集成分支与三个临时 worktree 已本地移除；最终仅保留 `dev` 与 `main` |

---

## 4. 影响边界

- **财务语义**：无改动；不触碰预算预占、结算、账本、关账、Flyway、金额计算。
- **API / 契约**：无改动；未新增 / 修改 OpenAPI、DTO 或错误码。
- **依赖**：无新增运行时依赖；`dayjs` 只是把既有实际安装版本显式声明，`echarts` 是零引用删除。
- **本地开发**：MySQL / Redis / MinIO 仍可从开发机本机访问，但不再暴露给同网段其他主机。
- **HC-01 harness**：崩溃遗留的固定名 Redis 容器不再被下一次 run 自动删除，需要人工清理；正常 run 的创建 / 清理行为不变。
- **CI**：CodeQL 的 JS/TS 分析范围扩大为 `scripts/**`；可能新增脚本类告警，届时按真实 finding 处理，不做 blanket ignore。
- **前端**：非法金额从“整页错误边界”降级为字段级“金额格式异常”；工作台可见性语义不变，但导航对象不再携带幽灵权限码。

---

## 5. 变更文件清单

```text
.github/codeql/codeql-config.yml
AGENTS.md
README.md
compose.dev.yaml
frontend/package.json
frontend/package-lock.json
frontend/src/app/layout/appNavigation.tsx
frontend/src/app/layout/appNavigation.test.tsx
frontend/src/features/allocation/AllocationEditor.tsx
frontend/src/features/allocation/AllocationEditor.test.tsx
frontend/src/features/allocation/shared/AllocationLinesEditor.tsx
frontend/src/features/auth/AuthPages.tsx
frontend/src/features/intelligence/decimal.ts
scripts/perf/hc01/ownership.mjs                  （新增）
scripts/perf/hc01/runner.mjs
scripts/perf/hc03/ownership.mjs                  （向后兼容扩展）
scripts/tests/hc01-ownership.test.mjs            （新增）
scripts/tests/nav-permission-catalog.test.mjs    （新增）
docs/03-acceptance/maintenance/2026-11/2026-11-12-remaining-p2-p3-cleanup.md
docs/03-acceptance/maintenance/2026-11/2026-11-12-deep-code-review.md（追加处置记录）
docs/03-acceptance/README.md
CHANGELOG.md
```

---

## 6. 结论

批次 A 的 7 项低风险发现全部按“测试先行、最小修复”完成，未发现审查报告与本批代码存在事实冲突，无需更正审查报告。本地脚本 / 前端 / 后端 / 网关单元测试与前端构建全部通过，依赖锁可干净安装，dev Compose 端口已收敛到回环地址；PR #184 的远程 CI 18/18 与 Security 3/3 进一步确认 CodeQL `scripts` 覆盖生效。唯一保留的 `NOT_VERIFIED` 是 P2-04 的真实 Docker 容器生命周期，留待后续 HC-01 实跑确认。
