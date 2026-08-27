# M9 Branch / PR Strategy

M9 follows the existing repository governance model: short-lived branch, PR, CI, optional peer review, squash merge.

Recommended branches:

```text
AIC-074  fix/m9-audit-closure
AIC-075  feat/m9-production-config
AIC-076  feat/m9-application-metrics
AIC-077  feat/m9-observability-stack
AIC-078  test/m9-browser-e2e
AIC-079  chore/m9-security-ci
AIC-080  feat/m9-backup-restore
AIC-081  perf/m9-scale-evidence
AIC-082  test/m9-provider-certification
AIC-083  release/v1.1.0
```

Rules:

```text
sync main before branch creation
one principal AIC scope per PR
no unrelated refactor
no broad Gateway work under M9
preserve frozen V1 evidence
record real execution evidence before acceptance claims
```

AIC-077 starts after AIC-076 metrics are merged. AIC-083 starts only when AIC-074–082 required evidence is available.
