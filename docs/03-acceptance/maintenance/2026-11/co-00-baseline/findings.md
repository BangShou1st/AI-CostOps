# CO-00 代码、分支与安全基线 — 核验结论

> 日期：2026-11-11
> 关联计划：`docs/02-development/designs/maintenance/2026-11-10-backend-reliability-refactor-design.md` §3 / §9 CO-P0
> 结论：**PASS**（安全基线已修复并在 `main` 目标提交重新验证）

## 1. 基线对账

| 项 | SHA / 值 |
|---|---|
| 修复前 `main` 基线 | `1fb5e74cff4b84081ff90a8717f8912b058aa793` |
| 安全修复汇入提交 | `a8fe98779023194b0bc49145a1289e98ff54063b`（合并 `perf/ci-acceleration`） |
| 当前 `main` / `dev` | `57696e25ca444658ec114a8152e7224f2fa67e45` |
| `perf/ci-acceleration` 被测 SHA | `e2aa26ece44eeda3517be0f91b27096b3e373590` |

`main` 与 `dev` 指向同一提交；`main` 是 `dev` 的历史祖先关系已收敛，不存在分叉。`perf/ci-acceleration` 的 CI 成功（run `42578509717`，SHA `e2aa26e`）只作为该分支的历史证据，不作为 `main` 的当前结论。

## 2. 安全发现与修复

### 2.1 follow-redirects（Trivy HIGH）

- 失败基线：`main @ 1fb5e74` 的 Security run `42646521102` 失败，Trivy 指向 `frontend/package-lock.json` 中 `follow-redirects`，CVE-2026-116689（HIGH），修复版本 1.16.2。
- 依赖路径：`axios@1.20.0` 声明 `follow-redirects: ^1.16.0`，锁文件此前解析到受影响的 1.16.1。
- 修复方式：在 `frontend/package.json` 的 `overrides` 中显式锁定 `follow-redirects: 1.16.2`，并更新 `package-lock.json`；未使用 `--force`，未文本替换版本号。
- 同批安全钉版：`backend/pom.xml` 的 `dependencyManagement` 将 `guava` 固定为 `33.7.2-jre`、`snappy-java` 固定为 `1.1.10.9`。

### 2.2 未做 blanket ignore

`.trivyignore` 为空文件；本轮没有新增任何忽略项，`Accepted risks: NONE`。CodeQL 与 Trivy 的判定相互独立，workflow 权限保持 `contents: read`，仅 CodeQL 作业拥有 `security-events: write`。

## 3. 重新验证

| 验证 | 结果 |
|---|---|
| `main` CI（run `42951085970`，push，SHA `57696e2`） | **success**，14/14 作业 |
| `main` Security（run `42951085983`，push，SHA `57696e2`） | **success**，3/3 作业 |
| Trivy（vuln + misconfig + secret，HIGH/CRITICAL，exit-code 1） | **success**，0 个未处置 HIGH/CRITICAL |
| CodeQL `java-kotlin` / `javascript-typescript` | **success** |
| 前端依赖安装 / lint / test / build | 由 `frontend-quality (lint & build)` 与 `frontend-test` 覆盖并通过 |

## 4. 复现命令

```bash
gh run view 42951085970 --json jobs --jq '.jobs[] | "\(.name): \(.conclusion)"'
gh run view 42951085983 --json jobs --jq '.jobs[] | "\(.name): \(.conclusion)"'
gh run view 42646521102 --log | grep -i "follow-redirects\|CVE-2026-116689"
```

## 5. 边界与未验证

- 本证据只覆盖 `main @ 57696e2` 的 GitHub Actions 结果；不代表生产部署或真实 Provider 调用安全。
- 真实 Provider 端到端仍为 `NOT_VERIFIED`（沿用 `docs/03-acceptance/maintenance/2026-10/2026-10-22-opencode-zen-minimax-real-browser-acceptance.md` 的历史先例，本轮未重跑）。
- 500k 规模与性能数据不在 CO-00 范围，见 CO-04 证据。
