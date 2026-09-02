# V2 Detailed Design — M10 Source of Truth

> Milestone: **M10 — V2 Detailed Design**  
> Status: **DESIGN IN PROGRESS — NOT YET FROZEN**  
> Runtime feature coding remains blocked until AIC-093 passes.

## 1. Authority order

V2 implementation decisions use this order:

```text
V1/V1.1 frozen financial invariants
→ M10 V2 Detailed Design
→ machine-readable API contracts
→ M11+ implementation plans / Issues
→ production code
```

If a lower layer conflicts with a higher layer, the lower layer must be changed. Do not change a financial invariant merely to match an implementation draft.

Control Plane HTTP machine contract:

```text
../api/openapi.yaml
```

Gateway Data Plane HTTP machine contract after AIC-092:

```text
../api/gateway-openapi.yaml
```

Business state, transaction, ownership, idempotency, Redis/MySQL and failure semantics are defined by this detailed-design set.

## 2. Inherited invariants

```text
MySQL = durable identity + financial truth
Redis != financial truth
POSTED Ledger = immutable
Correction = append-only
already-incurred Provider cost must not disappear
```

V1 budget:

```text
Available
= Total
- Actual
- Outstanding Commitments
```

V2 request-time budget view:

```text
Realtime Available
= Total
- Actual
- Outstanding Commitments
- Active Reservations
```

`Active Reservations` are short-lived control state, but their correctness is MySQL-authoritative. Redis may coordinate/cache; Redis does not independently authorize financial spend.

## 3. Runtime direction

```text
Monorepo
├─ frontend/   React / TypeScript Admin UI
├─ backend/    Java / Spring MVC Control Plane
└─ gateway/    Java / Spring WebFlux + Reactor Netty Data Plane
```

Principle:

```text
one monorepo
two deployables
one final financial truth
```

The current repository runtime baseline stays Java 21 + Spring Boot 4.1.0 unless a later implementation PR separately verifies and approves a dependency update. M10 does not silently upgrade dependencies.

## 4. M10 documents

| Stable ID | Document | Status |
|---|---|---|
| AIC-084 | `01-scope-runtime-boundary.md` | design |
| AIC-085 | `02-credentials-catalog-pricing.md` | design |
| AIC-086 | `03-request-state-machine.md` | design |
| AIC-087 | `04-budget-redis-atomicity.md` | design |
| AIC-088 | `05-provider-streaming-metering.md` | design |
| AIC-089 | `06-settlement-financial-boundary.md` | design |
| AIC-090 | `07-routing-resilience.md` | design |
| AIC-091 | `08-security-observability-deployment.md` | design |
| AIC-092 | `09-data-api-migration-testing.md` | design |
| AIC-093 | `../../03-acceptance/m10-design-freeze-matrix.md` | final freeze |

## 5. Supporting M10 design governance

Program baseline:

```text
../../superpowers/specs/2026-09-02-m10-v2-detailed-design-program.md
```

Independent architecture review decisions:

```text
../../superpowers/specs/2026-09-02-m10-independent-architecture-review.md
```

Execution plan:

```text
../../superpowers/plans/2026-09-02-m10-v2-detailed-design-plan.md
```

The independent review decisions are mandatory M10 inputs. AIC-093 cannot pass if a correctness-critical review decision is left to implementation-time guesswork.

## 6. M10 scope guard

Allowed:

```text
docs/**
PROJECT_CONTEXT.md / roadmap / README only after final M10 acceptance
```

Not allowed in the M10 design branch:

```text
Gateway production code
Backend/Frontend feature code
Flyway production migrations
runtime dependency updates
Docker runtime service additions
Redis production Lua scripts
Provider production adapters
```

## 7. Freeze rule

Every M11-blocking design topic must end AIC-093 as:

```text
FROZEN
```

A genuinely non-blocking future feature may be:

```text
DEFERRED WITH EXPLICIT NON-BLOCKING BOUNDARY
```

Any correctness/API/data-ownership decision that still says “implementation decides” is:

```text
BLOCKED
```

M10 is not complete until the final freeze matrix is accepted.
