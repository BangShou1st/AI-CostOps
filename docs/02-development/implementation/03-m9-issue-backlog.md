# M9 / V1.1 Production Foundation — Stable Issue Backlog

> 本文件定义 M9 的稳定计划编号。AIC stable ID 与 GitHub Issue # 是两套编号；当前实际映射如下。

设计基线：

```text
docs/superpowers/specs/2026-08-27-v1-to-v2-production-gateway-design.md
```

实施计划：

```text
docs/superpowers/plans/2026-08-28-m9-production-foundation-implementation-plan.md
```

## M9 Stable IDs / GitHub Issues

| Stable ID | GitHub | Title | Suggested Branch | Depends |
|---|---:|---|---|---|
| AIC-074 | #106 | Close high-value audit gaps | `fix/m9-audit-closure` | V1.0.1 |
| AIC-075 | #107 | Harden production configuration | `feat/m9-production-config` | V1.0.1 |
| AIC-076 | #108 | Add bounded application metrics | `feat/m9-application-metrics` | V1.0.1 |
| AIC-077 | #109 | Add Prometheus Grafana and alert smoke | `feat/m9-observability-stack` | AIC-076 / #108 |
| AIC-078 | #110 | Automate critical browser E2E | `test/m9-browser-e2e` | V1.0.1 |
| AIC-079 | #111 | Add continuous security CI | `chore/m9-security-ci` | V1.0.1 |
| AIC-080 | #112 | Implement backup and restore drills | `feat/m9-backup-restore` | V1.0.1 |
| AIC-081 | #113 | Establish import and reporting scale evidence | `perf/m9-scale-evidence` | V1.0.1 |
| AIC-082 | #114 | Certify one real Provider import | `test/m9-provider-certification` | certification harness + local real export |
| AIC-083 | #115 | Final M9 acceptance and v1.1.0 release closure | `release/v1.1.0` | AIC-074–082 |

## Scope Guard

M9 不实现：

```text
/gateway runtime
Spring WebFlux / Reactor Netty Gateway
Realtime Budget Reservation
Realtime Metering / Settlement
Provider Routing
RabbitMQ / Kafka by default
Kubernetes
```

这些属于 M10+；Gateway 大规模 feature coding 必须等 M10 Detailed Design 完成人工审查与冻结。

## Wave Order

```text
Wave 1: AIC-074 #106 / AIC-075 #107 / AIC-076 #108
Wave 2: AIC-077 #109 / AIC-078 #110 / AIC-079 #111
Wave 3: AIC-080 #112 / AIC-081 #113 / AIC-082 #114
Wave 4: AIC-083 #115
```

所有 Issue 的具体文件、TDD 步骤、测试命令和验收证据以实施计划为准；本文件只维护稳定 ID、实际 Issue 映射与依赖关系。
