# 2026-11-12 CI push 比较基准与前端测试定时器清理

| 项目 | 内容 |
|---|---|
| 日期 | 2026-11-12 |
| 责任人 | AI（DeepSeek Harness） |
| 关联模块 | GitHub Actions `ci.yml` 变更检测、Frontend Vitest 全局 teardown |
| 缺陷来源 | [P1 加固修复记录 §2.1](2026-11-12-p1-hardening-fixes.md)（paths-filter base）与 CI run [43140181777](https://github.com/BangShou1st/AI-CostOps/actions/runs/43140181777)（`frontend-test` 未处理定时器） |
| 代码基线 | `dev` = `acbf6b7`（`main` = `75f02ea`） |
| 关联分支 | `fix/ci-push-base-and-frontend-timer-teardown` |
| 变更性质 | CI 比较基准修正 + 测试 teardown 加固；无业务代码、无 API、无 Flyway、无依赖变更 |
| 总体判定 | **PASS**（本地 + [PR #182](https://github.com/BangShou1st/AI-CostOps/pull/182) 全部检查项）；合并后 `docs_only` 短路由纯文档 push 复验 |

---

## 1. 变更概述

### 1.1 `dorny/paths-filter` 在 `dev` push 上按 `main` 比较

- **位置**：`.github/workflows/ci.yml` 的 `detect-changes` 步骤
- **Before**：`dorny/paths-filter@v3` 未设置 `base`，默认以仓库默认分支（`main`）为基准。`dev` 领先 `main`
  时，每次 dev push 都会把“分支相对 main 的全部提交”当作变更集：
  - 纯文档推送 `acbf6b7` 仍被检测为 26 个文件，跑完整套件（6 分钟）；
  - `docs_only` 短路在 dev push 上永远不生效。
- **根因**：`paths-filter` 的默认 `base` 是默认分支；对长生命周期分支（dev）应显式 `base: ${{ github.ref }}`，
  让 push 比较“同一分支上一提交”。该参数在 `pull_request` 事件上会被忽略，不影响 PR 的 base..head 检测。
- **After**：`detect-changes` 增加 `base: ${{ github.ref }}` 与说明注释。
- **预期效果**：dev 上的纯文档 push 只命中 `docs_only`，其余作业跳过；含代码的 push 仍按本次推送比较，
  行为与旧逻辑一致（只是不再把历史提交重复计入）。

### 1.2 `ProviderImportUploadModal.test.tsx` 的未处理定时器导致 CI 红

- **位置**：`frontend/src/test/setup.ts`（全局 teardown）
- **Before**：`useDelayState`（`@rc-component/util`，被 antd `Form.Item` 的 `useDebounce` 使用）在表单
  值/错误变化时调度 `ms: 0` 或 `ms: 10` 的 `setTimeout`。测试恰好在定时器触发前结束，回调在 jsdom
  teardown 之后执行，Vitest 以 `Unhandled Errors → ReferenceError: window is not defined` 判整个文件失败
  （[CI 43140181777](https://github.com/BangShou1st/AI-CostOps/actions/runs/43140181777) 的 `frontend-test`）。
- **根因**：`useDelayState` 没有卸载清理；测试结束后仍有短定时器在飞。仓库里原有的 `window` getter
  guard（`2026-10` 的 test teardown fix）只兜住“window 被读取”的一种形态，不能阻止定时器在 teardown 后执行。
- **After**：`setup.ts` 增加全局 `afterEach`：
  - 若当前为 fake timers，先 `vi.useRealTimers()`（避免等待本身被 fake 而挂死）；
  - 再等待 20ms 真实宏任务，让 0/10ms 的 antd 定时器在 jsdom 仍存活时执行完。
  成本约 `20ms × 用例数`（当前 553 个用例约 +11s）。
- **取舍**：这是“让异步副作用在环境销毁前收敛”的测试卫生修复，不是忽略错误；没有改 `dangerouslyIgnoreUnhandledErrors`
  或任何断言。

---

## 2. 验证环境与执行命令

环境：Windows + PowerShell，Node `v24.14.0`，npm `11.9.0`。

| # | 命令 | 结果 |
|---|---|---|
| 1 | `cd frontend; npm run lint` | 通过，无输出 |
| 2 | `cd frontend; npx vitest run src/features/imports/upload/ProviderImportUploadModal.test.tsx`（连续 3 次） | 3/3 次 `6 passed`，无 unhandled error |
| 3 | `cd frontend; npx vitest run src/features/imports/ImportWorkflow.test.tsx`（fake timers 用例） | `23 passed`，无挂起 |
| 4 | `cd frontend; npm test -- --run` | 见 §2.1 |
| 5 | `cd frontend; npm run build` | 见 §2.1 |
| 6 | `npm run verify:links` | 见 §2.1 |

### 2.1 全量结果

| 检查 | 结果 |
|---|---|
| `cd frontend; npm test -- --run` | `73 files / 553 tests passed`，`Duration 217.39s`，无 unhandled error |
| `cd frontend; npm run build` | `tsc -b` + Vite，`built in 1.01s` |
| `npm run verify:links` | `247 Markdown files / 402 links / 0 broken` |

远程 PR 检查项（`ci.yml` 新语义、完整 backend/gateway/frontend 套件）以 PR 页面为准；合并后另以一次
纯文档 push 验证 `base: ${{ github.ref }}` 的 `docs_only` 短路。

### 2.2 远程结果

| 项 | 结果 |
|---|---|
| [PR #182](https://github.com/BangShou1st/AI-CostOps/pull/182) | 18 项检查全部 pass：`detect-changes`、`backend-unit`、`backend-integration` 两片、`gateway-unit`、`gateway-integration`、`frontend-quality`、`frontend-test`（3m17s）、`scripts-unit` 两个平台、`scripts-lifecycle`、`docker-build`、`browser-e2e`、`ci-status`、`trivy`、CodeQL 两项 |
| 合并 | squash merge 为 `5ceec9a`，源分支已删除 |
| 合并后文档 push | 见 §2.3（`docs_only` 短路复验） |

### 2.3 `docs_only` 短路复验

合并后本记录自身的更新以纯文档 push `fe26a79` 提交，结果：

| 证据 | 值 |
|---|---|
| run | [CI 43145593559](https://github.com/BangShou1st/AI-CostOps/actions/runs/43145593559) |
| `detect-changes` | `Detected 1 changed files`（修复前同一类 push 为 26 个） |
| `ci-status` | `All required checks passed (docs_only=true)` |
| 墙钟 | 44s（修复前同类 push 约 6 分钟） |

注：合并提交本身的 push run（43145519872）被 `concurrency.cancel-in-progress` 取消——dev 分支连续
push 的既有行为，与本次改动无关。

---

## 3. 结论与影响边界

- **CI**：`dev` push 的变更检测从“相对 main”改为“同一分支上一提交”，减少纯文档/小改动的全量运行；
  `main` push 仍比较上一提交；`pull_request` 行为不变。
- **前端测试**：每个用例后多 20ms 收敛窗口；不改变任何业务代码、组件行为或断言。
- **未验证**：远程 `ci.yml` 的新语义要等本次 PR 的 push/merge 后由实际运行确认；本文件在合并前不声称远程已验证。
- **回滚**：两处都是单行/单块级改动，可直接 revert 本 PR。
