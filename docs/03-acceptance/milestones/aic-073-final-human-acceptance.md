# AIC-073 — Final Human Acceptance / Release Sign-off

- 日期：2026-08-24
- 发布基线：`main@a79b2b96b6e80a465cf60098c1a46aee026b0836`
- 目标版本：`v1.0.0`
- 决策：**ACCEPTED / RELEASE GO**

## 1. Sign-off scope

AIC-073 是项目内部稳定计划编号，不等同于 GitHub Issue #73。它是 V1 发布前最后一道人工验收门，用于确认已有实现、自动化证据、浏览器 UAT、已知限制与发布边界均已被审阅并接受。

AIC-073 不新增业务功能，不修改财务 truth model，不扩展 Provider 范围。

## 2. Release baseline

最终验收基线为：

```text
main = a79b2b96b6e80a465cf60098c1a46aee026b0836
PR #99 = FAILED Import terminal cancellation / close blocker fix
PR #100 = ignore local .zcode directory
PR #101 = release evidence + Daily Dev documentation closure
```

验收时仓库工作树 clean，`main` 与 `origin/main` 同步。

## 3. Automated evidence

当前最终证据：

| Check | Result |
|---|---|
| Backend unit | 437 PASS |
| Backend architecture | 34 PASS |
| Backend integration | 800 PASS |
| Frontend Vitest | 47 files / 432 tests PASS |
| Frontend lint | PASS |
| Frontend build | PASS |
| Required PR CI | 7/7 PASS |
| Current-head Docker image build in GitHub CI | PASS |

GitHub CI 对 PR #101 head 成功执行 backend/frontend Docker image build，因此本次本机网络失败不构成 Dockerfile 或依赖图失败证据。

## 4. Human UAT evidence

浏览器/API UAT：

```text
Functional scenarios     32/32 PASS
Critical state branches  14/14 PASS
P0/P1 defects             0
```

关键闭环已验证：

```text
FAILED Import
→ explicit CANCELED
→ OPEN_IMPORTS blocker cleared
→ 7/7 close checks PASS
→ Period CLOSED
→ financial write rejected with 409 PERIOD_NOT_OPEN
→ Reopen PASS
→ Period OPEN
```

RBAC、费用状态机、预算/承诺、分摊、账本 posting/correction、对账案例处理与审计链路均已纳入 UAT。

## 5. Runtime smoke evidence

### Daily Dev

最终 Daily Dev 模式实际验证通过：

```text
Docker infra: MySQL / Redis / MinIO healthy
Local Backend: localhost:8080 PASS
Local Frontend: localhost:5173 PASS
Backend liveness: 200 / UP
Login via Vite proxy: PASS
Workbench render/data: PASS
```

### Full Compose

AIC-071 已记录完整 Full Compose `SMOKE_V1_PASS`、fresh-volume bootstrap 与 restart persistence PASS。

2026-08-24 在最终 `main` 上再次尝试本机 Full Compose rebuild 时，backend image 在 Maven `dependency:go-offline` 外部依赖下载阶段因网络速度极低而中止；应用没有进入启动阶段，因此没有产生新的产品失败证据。

本次本机重复 Full Compose smoke **waived**，理由：

1. 历史完整 Compose smoke 已 PASS；
2. 当前代码 head 的 GitHub CI `docker-build` 已 PASS；
3. PR #101 对 Full Compose runtime architecture / Dockerfile / compose.yaml 没有功能性修改；
4. 最终 Daily Dev smoke 已实际 PASS；
5. 失败根因被限定为外部依赖下载网络条件。

该 waiver 必须保留在发布证据中，不得表述为“最终 main 本机 Full Compose 再次执行并 PASS”。

## 6. Accepted limitations

以下限制被明确接受，不阻塞 V1：

- 本地环境未验证真实 SMTP 投递；开发使用 file-backed mailbox。
- Provider fixture / benchmark 不代表真实企业生产账单认证。
- import benchmark 最大实测 1,024 rows，不外推为生产容量承诺。
- provider-account 与 allocation-rule 部分 audit producer 仍有非阻塞 follow-up。
- 部分 session/reconciliation/close evidence 为 flow-level evidence，而非每个底层事件均有直接 assertion。
- Docker Desktop / WSL2 本机磁盘高水位属于开发机维护问题，不影响产品正确性或发布基线。

## 7. Final decision

基于以上证据：

```text
AIC-073: COMPLETED
Human acceptance: ACCEPTED
Release blocker: NONE
P0/P1: 0
RELEASE_READY: YES
```

批准以本 sign-off PR 合并后的 `main` HEAD 作为 `v1.0.0` tag / GitHub Release 的唯一发布提交。

在 tag 创建前仍需确认：

- sign-off PR 已合并；
- merge 后 `main` CI/状态无异常；
- tag `v1.0.0` 尚不存在。

除此之外，不要求再次本机 Full Compose rebuild。