# M11 Gateway Edge MVP — Acceptance Evidence

> **Status: IN PROGRESS — Task 9 skeleton, final numbers recorded at Task 10 fresh verification.**
> No `PASS` is claimed for any check that has not actually run in the final acceptance pass.

## 1. Final branch HEAD

```text
<filled at Task 10>
```

## 2. Commit list

```text
<filled at Task 10>
```

## 3. Changed files / scope

| Area | Entry points |
|---|---|
| Gateway deployable | `gateway/*` WebFlux data plane |
| Backend | `V18__m11_gateway_edge_foundation.sql`, dev provisioning, Close blocker |
| CI/Security | `.github/workflows/ci.yml`, `.github/workflows/security.yml`, `.github/codeql/codeql-config.yml` |
| Docs | runbook + this evidence |

## 4–9. Test results (final pass)

| Suite | Result |
|---|---|
| Backend unit | <TBD> |
| Backend architecture | <TBD> |
| Backend integration | <TBD> |
| Gateway unit (surefire) | <TBD> |
| Gateway architecture | <TBD> |
| Gateway integration (failsafe + tagged) | <TBD> |
| Frontend lint/test/build | <TBD> |

## 10. Gateway Docker build

```text
<filled at Task 10>
```

## 11. Local application Docker build count

```text
local Docker application image builds performed: <actual count by image>
Gateway final Docker build: PASS/FAIL
repeated compose --build loop used: NO
global Docker prune used: NO
destructive volume cleanup used: NO
```

## 12. Mock provider smoke

```text
<filled at Task 10>
```

## 13. Real MiMo smoke

```text
<filled at Task 10; BLOCKED: missing external MiMo credential if AICOSTOPS_MIMO_API_KEY absent>
```

## 14. CI

```text
<filled at Task 10 after push + GitHub checks>
```

## 15. Security

```text
<filled at Task 10 after CI/Security run>
```

## 16. PR number + URL

```text
<filled at Task 10>
```

## 17. Remaining limitations / blockers

```text
<filled at Task 10>
```

## 18. git status

```text
<filled at Task 10>
```

## Frozen adversarial checklist (final pass)

```text
[ ] Provider I/O cannot happen before committed DISPATCH_INTENT
[ ] same idempotency identity cannot create a second billable attempt
[ ] same key + different raw body conflicts
[ ] post-dispatch 429/5xx/timeout/reset never auto-retries
[ ] client disconnect is CANCELED_AFTER_DISPATCH + BILLABLE_POSSIBLE
[ ] missing usage is never zero-filled
[ ] REQUIRED budget credential cannot bypass absent M12 Reservation
[ ] mandatory Redis limiter cannot fail open
[ ] JDBC/MyBatis is off Netty event loop
[ ] possibly billable M11 work blocks BillingPeriod Close
[ ] prompt/completion/secrets absent from logs/metrics/evidence
[ ] Gateway cannot run Flyway
[ ] no M12/M13/Ledger functionality leaked into M11
[ ] existing Backend tests green
[ ] existing Frontend lint/tests/build green
[ ] Gateway unit/integration/architecture tests green
[ ] Gateway OpenAPI contract tests green
[ ] Gateway Docker build green
[ ] CI and Security workflows cover Gateway
```