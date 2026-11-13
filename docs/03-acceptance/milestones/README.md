# 里程碑验收证据索引

本目录保存已封存的里程碑与版本验收证据。历史结论按当时时间点保留，不因后续版本改写；
当前状态以 [`PROJECT_CONTEXT.md`](../../../PROJECT_CONTEXT.md) 与 [`docs/README.md`](../../README.md) 为准。

部分历史记录保留了迁移前的路径（如 `docs/02-development/operations/`）；映射见
[`docs/README.md` § 历史路径映射](../../README.md#历史路径映射)。

## V1 / M9

| 文档 | 内容 |
|---|---|
| [m9-acceptance-matrix.md](m9-acceptance-matrix.md) | M9 验收矩阵总入口 |
| [m9-backup-restore-evidence.md](m9-backup-restore-evidence.md) | MySQL / Redis 备份恢复演练 |
| [m9-browser-e2e-evidence.md](m9-browser-e2e-evidence.md) | 浏览器端到端验收 |
| [m9-observability-evidence.md](m9-observability-evidence.md) | Prometheus / 日志 / 告警证据 |
| [m9-provider-certification-mimo.md](m9-provider-certification-mimo.md) | MiMo Provider 认证 |
| [m9-scale-evidence.md](m9-scale-evidence.md) | 规模与性能证据 |
| [m9-security-ci-evidence.md](m9-security-ci-evidence.md) | 安全与 CI 证据 |
| [aic-073-final-human-acceptance.md](aic-073-final-human-acceptance.md) | V1 最终人工验收与发布签署 |
| [aic-083-m9-final-acceptance.md](aic-083-m9-final-acceptance.md) | M9 最终收敛报告 |
| [v1-release-candidate-evidence.md](v1-release-candidate-evidence.md) | V1 Release Candidate 证据 |

## V2 / M10 ~ M16

| 文档 | 内容 |
|---|---|
| [m10-design-freeze-matrix.md](m10-design-freeze-matrix.md) | V2 详细设计冻结矩阵 |
| [m11-gateway-edge-evidence.md](m11-gateway-edge-evidence.md) | Gateway Edge 与身份/路由入口 |
| [m12-identity-budget-reservation-evidence.md](m12-identity-budget-reservation-evidence.md) | 身份识别与预算预占 |
| [m13a-gateway-metering-evidence.md](m13a-gateway-metering-evidence.md) | 实时计量 |
| [m13b-gateway-settlement-evidence.md](m13b-gateway-settlement-evidence.md) | Gateway 结算 |
| [m14-multi-provider-routing-resilience-evidence.md](m14-multi-provider-routing-resilience-evidence.md) | 多 Provider 路由与弹性 |
| [m15-hybrid-reconciliation-evidence.md](m15-hybrid-reconciliation-evidence.md) | 混合对账 |
| [m16-v2-production-acceptance-evidence.md](m16-v2-production-acceptance-evidence.md) | V2 生产验收 |
| [m16-browser-uat-runbook.md](m16-browser-uat-runbook.md) | V2 浏览器 UAT 运行手册 |
| [m16-v2-production-acceptance-luna-execution-prompt.md](m16-v2-production-acceptance-luna-execution-prompt.md) | V2 验收执行提示词 |

## V3 / M17 ~ M22

| 文档 | 内容 |
|---|---|
| [m17-v3-design-freeze-matrix.md](m17-v3-design-freeze-matrix.md) | V3 设计冻结矩阵 |
| [m18-backend-contract-freeze.md](m18-backend-contract-freeze.md) | V3 后端契约冻结 |
| [m20-v3-production-acceptance.md](m20-v3-production-acceptance.md) | V3 生产验收 |
| [m21-v3-post-release-operational-readiness.md](m21-v3-post-release-operational-readiness.md) | 发布后运维就绪 |
| [m22-v3-production-certification.md](m22-v3-production-certification.md) | V3 生产认证与关闭记录 |

## 相关入口

```text
当前状态            → PROJECT_CONTEXT.md
验收与维护总索引    → docs/03-acceptance/README.md
维护期缺陷证据      → docs/03-acceptance/maintenance/YYYY-MM/
现行测试与 CI       → AGENTS.md §4 / .github/workflows/ci.yml
```
