# V2 Detailed Design — M10 Source of Truth

> Milestone: **M10 — V2 Detailed Design**  
> Status: **DESIGN IN PROGRESS — AIC-093 FINAL FREEZE NOT YET PASSED**  
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

Gateway Data Plane HTTP machine contract:

```text
../api/gateway-openapi.yaml
```

Business state, transaction, ownership, idempotency, Redis/MySQL and failure semantics are defined by this detailed-design set.

---

## 2. M10 consolidation precedence

AIC-084 through AIC-091 were intentionally written incrementally. AIC-092 is the **final consolidation contract** that turns those designs into exact schema/API/migration/test rules.

Therefore the interpretation order *inside M10* is:

```text
V1/V1.1 frozen invariant
→ AIC-092 exact consolidated contract
→ AIC-084..091 domain-specific explanation
→ Gateway OpenAPI machine shape
```

This does **not** allow AIC-092 to weaken an inherited financial invariant. It only resolves provisional choices or cross-document structure discovered while later AICs were designed.

The following supersessions are explicit and must not be treated as competing alternatives:

| Earlier provisional wording | Final AIC-092 rule |
|---|---|
| AIC-085 empty `gateway_credential_model` may mean all models | **Superseded:** credential model access is explicit-only / deny-by-default; at least one ACTIVE relation is required |
| AIC-085/086 Provider Account / Provider Model / Pricing Version / route decision described as persisted immutable request snapshot | **Superseded:** route-specific commercial truth lives on append-only `gateway_route_attempt`; `gateway_request` owns stable client/business identity only |
| AIC-086 request snapshot fields include route-specific Provider/Pricing ids | **Superseded:** request may point to `current_route_attempt_id` only as a convenience pointer; historical truth remains the attempt chain |
| AIC-087 `UNIQUE(org_id, request_id)` reservation identity | **Superseded:** Reservation is per Route Attempt with `UNIQUE(org_id, route_attempt_id)` plus one-effective-hold-per-request uniqueness |
| AIC-087 budget mode described conceptually | **Resolved:** `gateway_credential.budget_enforcement_mode = REQUIRED | OPTIONAL` is durable governed policy |
| AIC-088 Usage Fact may directly duplicate Provider Account / Provider Model identity and typed-vs-JSON dimensions was open | **Resolved:** Usage Fact points to `route_attempt_id`; normalized financial dimensions use typed `gateway_usage_dimension` rows; safe bounded provider metadata may use JSON |
| AIC-088 normalized dimension set called candidate | **Resolved for V2 Core:** `INPUT_TOKEN`, `OUTPUT_TOKEN`, `CACHED_INPUT_TOKEN`, `REQUEST` |
| AIC-089 exact financial lock order left to AIC-092 | **Resolved:** BillingPeriod → sorted Budgets → sorted Commitments → Gateway Reservation/Settlement source rows as applicable → V1 source/allocation rows when applicable → Ledger uniqueness/insertion |
| Earlier generic Gateway error treatment | **Resolved:** Control Plane retains ProblemDetail; Gateway `/v1` uses the OpenAI-compatible error envelope frozen in `gateway-openapi.yaml` |
| Earlier successful response examples imply usage is always present | **Resolved:** public success `usage` is optional; absent usage must become INCOMPLETE/UNKNOWN financial metering, never fabricated zero |

If another contradiction is found, AIC-093 must mark the topic `BLOCKED` until the documents are repaired. Implementers must not choose whichever wording is easier to code.

---

## 3. Inherited invariants

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
- Effective Active Reservations
```

Reservations are short-lived control state, but their correctness is MySQL-authoritative. Redis may coordinate/cache; Redis does not independently authorize financial spend.

---

## 4. Runtime direction

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

---

## 5. M10 documents

| Stable ID | Document | Status |
|---|---|---|
| AIC-084 | `01-scope-runtime-boundary.md` | FROZEN CANDIDATE |
| AIC-085 | `02-credentials-catalog-pricing.md` | FROZEN CANDIDATE, subject to AIC-092 supersessions above |
| AIC-086 | `03-request-state-machine.md` | FROZEN CANDIDATE, subject to AIC-092 supersessions above |
| AIC-087 | `04-budget-redis-atomicity.md` | FROZEN CANDIDATE, subject to AIC-092 supersessions above |
| AIC-088 | `05-provider-streaming-metering.md` | FROZEN CANDIDATE, subject to AIC-092 supersessions above |
| AIC-089 | `06-settlement-financial-boundary.md` | FROZEN CANDIDATE, exact lock/schema contract in AIC-092 |
| AIC-090 | `07-routing-resilience.md` | FROZEN CANDIDATE |
| AIC-091 | `08-security-observability-deployment.md` | FROZEN CANDIDATE |
| AIC-092 | `09-data-api-migration-testing.md` | FROZEN CANDIDATE — final consolidation |
| AIC-093 | `../../03-acceptance/m10-design-freeze-matrix.md` | FINAL FREEZE GATE |

---

## 6. Supporting M10 design governance

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

---

## 7. M10 scope guard

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

---

## 8. Design-vs-implementation evidence boundary

M10 freezes **what must be true**. M11+ supplies runtime implementation evidence.

Examples intentionally deferred as implementation evidence, not design decisions:

```text
GatewayOpenApiContractTest execution
actual Flyway migration numbers chosen from next free version
real MiMo sanitized certification run
load/soak thresholds
circuit breaker numeric thresholds
production SLO thresholds
```

Those items may not change the frozen financial/API/data-ownership semantics while being implemented.

The existing repository already uses SnakeYAML-based OpenAPI contract tests; M11 must add an equivalent Gateway-specific contract test for `gateway-openapi.yaml` before claiming the runtime implements this design.

---

## 9. Freeze rule

Every M11-blocking design topic must end AIC-093 as:

```text
FROZEN
```

A genuinely non-blocking implementation evidence item or future feature may be:

```text
DEFERRED WITH EXPLICIT NON-BLOCKING BOUNDARY
```

Any correctness/API/data-ownership decision that still says “implementation decides” is:

```text
BLOCKED
```

M10 is not complete until the final freeze matrix is accepted and applicable PR checks have been reviewed.
