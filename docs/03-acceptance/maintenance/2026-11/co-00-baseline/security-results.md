# CO-00 Security 结果（脱敏）

> 目标提交：`main @ 57696e25ca444658ec114a8152e7224f2fa67e45`
> Security run：`42951085983`（push，success）
> 本文不包含任何凭据、Token、Provider Key 或原始扫描文件。

## 1. 汇总

| 扫描 | 结果 | 说明 |
|---|---|---|
| Trivy filesystem（vuln + misconfig + secret，HIGH/CRITICAL，exit-code 1） | **PASS** | 0 个未处置 HIGH/CRITICAL |
| CodeQL `java-kotlin` | **PASS** | security-events 仅由 CodeQL 作业上传 |
| CodeQL `javascript-typescript` | **PASS** | 同上 |

`.trivyignore` 为空；本轮 `Accepted risks: NONE`，没有新增 blanket ignore。

## 2. 已修复的依赖风险

| 包 | 修复前 | 修复后 | 引入路径 | 关联 CVE |
|---|---|---|---|---|
| `follow-redirects` | 1.16.1 | **1.16.2**（`frontend/package.json` overrides + lockfile） | `axios@1.20.0` → `^1.16.0` | CVE-2026-111755 / CVE-2026-111756 / CVE-2026-116689 |
| `guava` | 版本漂移 | **33.7.2-jre**（后端 `dependencyManagement`） | backend Maven 依赖树 | 由 Trivy Maven 解析提示后钉版 |
| `snappy-java` | 版本漂移 | **1.1.10.9**（后端 `dependencyManagement`） | backend Maven 依赖树 | 同上 |

修复提交：`66c8e51`（follow-redirects）、`98bb591`（guava / snappy-java），随后随 `a8fe987` 汇入 `main`。

## 3. 失败基线（历史）

`main @ 1fb5e74` 的 Security run `42646521102` 在 Trivy 阶段失败，日志指向 `frontend/package-lock.json` 中 `follow-redirects` 1.16.1 / CVE-2026-116689（HIGH），修复版本 1.16.2。该失败已由上述钉版修复，并在 `main @ 57696e2` 的 Security run `42951085983` 重新验证为 **success**。

## 4. 边界

- 本结果证明 GitHub Actions 安全扫描在目标提交通过，不证明生产运行时无风险。
- 真实 Provider 凭据与真实计费回调不在本轮范围；仓库未提交任何真实 Key、`.env` 或 Provider 原始导出。
- 高危依赖升级仍应按 `docs/02-development/implementation/04-pr-ci-release-process.md` §10 独立 PR 处理；本轮钉版已包含在维护 PR 中并接受同一套 CI/Security 门禁。
