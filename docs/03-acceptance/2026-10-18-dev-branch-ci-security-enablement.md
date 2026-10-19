# dev 分支 CI/Security 启用与首轮远程验收（2026-10-18）

## 变更范围

在 `dev` 分支（基线 `6f8e2845a46e55ceed8af7cfc79ad5e3e924accf`）上，使每次 push 都自动运行 GitHub CI 与 Security：

- [ci.yml](file:///e:/project/AI-CostOps/.github/workflows/ci.yml) 与 [security.yml](file:///e:/project/AI-CostOps/.github/workflows/security.yml) 的 `push.branches` 增加 `dev`，保留 `main`。
- 保留原有 `pull_request` 触发、Security 每周定时扫描（`cron: 0 6 * * 1`）、测试矩阵与失败门禁，未做任何弱化。

提交 `32ff424f271a0a2c70eaf14ba5ecf677dde5f107`（2 files changed, 2 insertions），非强推：`6f8e284..32ff424  dev -> dev`。仅提交两个 workflow 文件；`.freebuff/`、`.trae/` 保持未跟踪、未提交。

## 依赖构建条件

- **OpenCode Zen（GitHub Packages，私有）**：`gateway/pom.xml`、`backend/pom.xml` 引用 `https://maven.pkg.github.com/BangShou1st/opencode-zen`，server id 为 `github`；凭据来自仓库 Secret `ZEN_MAVEN_TOKEN`（`gh secret list` 确认存在），Runner 上由 `actions/setup-java@v5` 写入 `~/.m2/settings.xml`。仓库内未硬编码任何 Token。
- **Kilo Gateway（JitPack，公开）**：`https://jitpack.io` 按 Git tag（`v0.2.1`）匿名消费，无需凭据。

## 工作流静态校验

以 YAML 解析器读取两个文件，结构与触发条件均正确：

| 文件 | push.branches | pull_request | schedule | jobs |
| --- | --- | --- | --- | --- |
| ci.yml | `[main, dev]` | 保留 | — | 14 |
| security.yml | `[main, dev]` | 保留 | `0 6 * * 1` | 3 |

## 远程结果（真实 GitHub Actions）

推送 `32ff424` 后，[CI](https://github.com/BangShou1st/AI-CostOps/actions/runs/39129531518) 与 [Security](https://github.com/BangShou1st/AI-CostOps/actions/runs/39129531524) 在 `dev` 分支被真实触发，整体结论均为 `success`。

CI（run 39129531518，14/14 作业成功）：`scripts-unit (ubuntu-latest)`、`scripts-unit (windows-latest)`、`scripts-lifecycle`、`backend-unit`、`backend-integration`、`backend-architecture`、`gateway-unit`、`gateway-integration`、`gateway-architecture`、`frontend-lint`、`frontend-test`、`frontend-build`、`docker-build`、`browser-e2e`（Playwright E2E 通过；失败时才上传产物的步骤按预期 skipped）。

Security（run 39129531524，3/3 作业成功）：`codeql (java-kotlin)`、`codeql (javascript-typescript)`、`trivy`（文件系统 vuln/misconfig/secret 扫描 + backend/frontend/gateway 三个镜像扫描全部通过）。

## 外部环境说明

- 所有作业出现一条信息性注解：`ubuntu-latest` 将于 2026-10-19 起迁移到 Ubuntu 26（[runner-images#14748](https://github.com/actions/runner-images/issues/14748)）。这是提醒类告警，不影响本次结果。
- 无外部环境阻断；无失败作业，因此未触发第 9 步的 CI/构建配置修复。

## 结论

`dev` 的 CI 与 Security 在 GitHub Actions 上真实全绿（`32ff424`）。PASS，停止并等待独立复审。
