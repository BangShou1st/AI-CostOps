# 04 — 生产与运维包 (Operations)

本目录是 AI-CostOps 生产部署、运行配置、可观测性监控、容灾与日常运维演练的唯一权威中心。

## 目录索引与职责

| 子目录 / 入口 | 核心内容 | 适用场景 |
|---|---|---|
| [`runbooks/`](runbooks/) | [V3 Operational Runbook](runbooks/v3-operational-runbook.md) | 维护期全链路自包含验证、拓扑启动、最小权限与端到端演练 |
| [`configs/`](configs/) | [生产环境配置规范](configs/01-production-configuration.md) | 生产环境拓扑参数、密钥配置与环境变量规格 (AIC-075) |
| [`observability/`](observability/) | [可观测性与监控告警](observability/02-observability.md) | Prometheus 业务指标、Grafana Dashboard、Logstash 规则 (AIC-077) |
| [`disaster-recovery/`](disaster-recovery/) | [备份恢复](disaster-recovery/03-backup-restore.md) 与 [Provider 认证](disaster-recovery/04-provider-certification.md) | MySQL/Redis 备份与灾难恢复演练 (AIC-080)、离线 Provider 真实认证 (AIC-082) |

---

## 运维演练与自包含验证快速入口

所有运维脚本位于仓库根目录 `scripts/ops/` 与 `scripts/m21/`。

### 1. 运行自包含 V3 拓扑验证
```powershell
# 生成本地 CSPRNG 密钥 (.env.m21.local)
pwsh -File scripts/m21/new-v3-operational-env.ps1

# 启动拓扑并执行最小权限验证
docker compose -f compose.yaml -f compose.v3-operational.yaml `
  -p aicostops-m21-final-r3 --env-file .env.m21.local up -d --build
```

详见 [V3 Operational Runbook](runbooks/v3-operational-runbook.md)。
