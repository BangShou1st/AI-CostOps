# M13-A — Provider Metering & Immutable Usage Facts

> Issue: #137
> Branch: `feat/m13a-gateway-metering`
> Frozen base: `7cd80cdf55d3aec279971f72dd423ba6f68c5272`
> Implementation SHA before this evidence commit: `393b782149690b17cd12cb2ddbc8a78cc7c93ef8`
> Scope: M13-A only; M13-B settlement is not implemented.

## Scope and schema

Backend remains the sole Flyway owner. Exactly one migration was added:

```text
backend/src/main/resources/db/migration/V20__m13_gateway_usage_fact.sql
```

V1–V19 are unchanged. V20 adds `gateway_usage_fact`,
`gateway_usage_dimension`, and the same-organization request pointer FK. The
schema enforces immutable revisions, `(org_id, request_id, sequence)`
uniqueness, at most one `FINAL` fact per request, non-negative
`DECIMAL(30,8)` quantities, the four frozen dimension codes, and bounded
provenance/status values.

## Runtime behavior

- Provider stream parsing uses normalized `DELTA`, `METERING`, and `DONE`
  events. MiMo usage-only frames are retained as bounded state and are never
  forwarded as completion content.
- Classification reads the frozen `pricing_rate` dimensions. Missing cached
  input or other required evidence becomes `INCOMPLETE`; no evidence or
  malformed/unsupported evidence becomes `UNKNOWN`. No total-token subtraction
  or other inferred quantity is performed.
- Non-stream and stream success both commit the usage fact, dimensions,
  request pointer, and terminal lifecycle state before returning success or
  downstream `[DONE]`.
- Post-dispatch failure appends best available `UNKNOWN`/`INCOMPLETE`/`FINAL`
  evidence and moves an `ACTIVE` reservation to `PENDING_HOLD`; it never
  releases or finalizes money.
- Effective time uses Provider billing timestamp, Provider request timestamp,
  then durable Gateway dispatch intent. Safe metadata is allowlisted and
  bounded to 8 KiB; prompts, completions, reasoning, credentials, and raw
  provider bodies are not persisted or emitted in metrics.

## Local verification

Environment: Java 21.0.10 LTS, Maven 3.9.11, Windows 11, MySQL 8.4 and Redis
Testcontainers.

Gateway:

```text
./mvnw -B -DexcludedGroups=architecture,integration test  PASS — 95
./mvnw -Dtest=GatewayArchitectureTest test                 PASS — 7
./mvnw -B -Dgroups=integration verify                      PASS — Failsafe 57
```

Backend:

```text
./mvnw -B -DexcludedGroups=architecture,integration test  PASS — 470, 1 skipped
./mvnw -B -Dgroups=architecture test                      PASS — 34
./mvnw -B -Dgroups=integration verify                     844/845 pass
```

The single full-Backend-suite failure was the pre-existing
`ImportWorkflowReadApiIntegrationTest.evidenceImportsPageAndCountShareTheSameTenantConsistentDataset`
string assertion, where an auto-incremented foreign account ID collided with
the asserted batch-ID substring. The test class was isolated and rerun:

```text
./mvnw -B -Dit.test=ImportWorkflowReadApiIntegrationTest verify  PASS — 11
```

M13-focused tests all passed: schema/nearby Backend checks 28; MiMo adapter 9;
classifier 9; finalization 5; controller 15; streaming failure/success 9;
metrics plus classifier 10; Gateway architecture 7.

Additional checks:

```text
git diff --check origin/main...HEAD  PASS
```

No V1–V19 migration diff exists, and no M13-B settlement, Ledger posting,
Budget Actual/Commitment consumption, M14, M15, or FX implementation is
present.

## Publication

This evidence is recorded by the commit that adds this file. PR and hosted CI
status are reported in the task handoff after publication; no merge is
authorized by this work item.
