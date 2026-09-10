# M17 — V3 Design Freeze Matrix

> Issue: #153  
> Baseline: `v2.0.0` / `main@93e359687c2d0e81b096d7a73cc8e04e16a7ecbe`  
> Design branch: `docs/m17-v3-design-freeze`

Normative design sources:

```text
docs/superpowers/specs/2026-09-10-m17-v3-model-provider-hub-cost-intelligence-design.md
docs/superpowers/specs/2026-09-10-m17-v3-runtime-contract-clarifications.md
docs/01-blueprint/product/12-v3-roadmap.md
```

## Freeze matrix

| Area | Frozen decision | Gate |
|---|---|---|
| V3 product | Model Provider Hub + deterministic Cost Intelligence + optional AI Advisor | PASS |
| Development order | M18 backend complete/OpenAPI freeze before M19 frontend | PASS |
| ai-collab reuse | Product intent only; per-model ModelConfiguration shape is not copied | PASS |
| spec-agent reuse | OpenCode provider-template/transport lessons reused and hardened | PASS |
| Provider domain | Reuse Provider Account/Credential/Model/Pricing/Routing; no parallel LLM domain | PASS |
| Connection lifecycle | Versioned DRAFT -> ACTIVE -> RETIRED, activated versions immutable | PASS |
| V2 migration compatibility | Existing routable accounts receive ACTIVE v1 connection-profile backfill | PASS |
| Runtime endpoint authority | Post-V24 endpoint comes from ACTIVE connection profile, not `provider_catalog.base_url` | PASS |
| Custom protocol | OpenAI-compatible Chat Completions only in V3.0 | PASS |
| OpenCode protocol | Only positively classified Chat Completions models routable in V3.0 | PASS |
| OpenCode discovery | `/models` proves availability, not price/protocol truth | PASS |
| Free pricing | Name suffix never creates financial truth; explicit Pricing Version remains authority | PASS |
| OpenCode network | DIRECT_ONLY; poison proxy `127.0.0.1:7897` must receive zero requests | PASS |
| Custom network | DIRECT_PUBLIC_ONLY with SSRF validation on initial target and redirects | PASS |
| Private endpoints | Not browser-enabled in V3.0 | PASS |
| Arbitrary headers | Not supported; bounded auth/header policy only | PASS |
| Credential storage | Existing encrypted `provider_credential` boundary reused | PASS |
| Secret projection | Raw Provider secret/ciphertext/Authorization never returned/logged | PASS |
| Custom model ownership | Organization-private logical/provider model namespace | PASS |
| Same model id on two connections | Private `provider_model` identity includes exact Provider Account | PASS |
| Model discovery | Observation is connection-profile-version scoped and never routing truth | PASS |
| Capabilities | Declared and backend-verified states separated | PASS |
| Probe behavior | Explicit/controlled; no autonomous hidden-spend high-frequency polling | PASS |
| Routing lineage | Post-V24 Route Attempt freezes exact connection-profile id | PASS |
| Financial truth | Ledger/Settlement/Pricing remain authoritative; no analytics ledger | PASS |
| Currency | No implicit FX; analytics grouped/evaluated within currency | PASS |
| Anomaly | Median/MAD/robust-z + percent + absolute materiality gates | PASS |
| Forecast | Damped Holt primary, recent run-rate bounded fallback | PASS |
| Budget risk | Immediate exposure separated from projected period-end | PASS |
| Savings | Exact counterfactual pricing within same logical-model semantics | PASS |
| Savings action | Human-governed; no automatic routing activation | PASS |
| Recommendation history | Evidence/version/fingerprint retained; lifecycle explicit | PASS |
| Advisor facts | Server-generated bounded evidence envelope | PASS |
| Advisor numbers | LLM cannot author authoritative money values | PASS |
| Advisor failure | Deterministic intelligence remains available | PASS |
| Advisor execution | Gateway-governed, metered, budgeted, settled and Ledger-visible | PASS |
| Advisor worker | Durable claim/fencing/recovery; no Provider I/O in claim transaction | PASS |
| Advisor redispatch | No hidden redispatch after linked billable-possible execution | PASS |
| Advisor retry | Explicit user retry creates new append-only governed attempt | PASS |
| Advisor feedback loop | Advisor cost included in totals, excluded from recursive anomaly/savings roots by default | PASS |
| Advisor permissions | `AI_ADVISOR_USE` and `AI_ADVISOR_MANAGE` separate AI-spend authority from read rights | PASS |
| Provider permissions | Reuse `PROVIDER_ACCOUNT_READ/MANAGE` | PASS |
| Audit | Provider connection/model/credential + Advisor/recommendation actions covered | PASS |
| Frontend UX | Provider Gallery + guided connection wizard + provider detail | PASS |
| Provider branding | Built-ins use reviewed bundled brand asset; custom uses generated initials/icon | PASS |
| Product Design | Exactly three materially different visual directions before implementation | PASS |
| V3 migrations | Start at V24; V1–V23 immutable | PASS |
| New infrastructure | No Kafka/K8s/Elasticsearch/Python ML without measured reopening | PASS |
| Release | Target `v3.0.0`; unavailable paid real-provider gate is deferred, never relabeled PASS | PASS |

## Reopen triggers

M17 must be reopened before M18 implementation continues if any of these become required:

```text
Responses API / Anthropic Messages / Gemini-native protocol
private/local model endpoint support
arbitrary custom headers or request transform DSL
browser-configurable proxy
new financial truth store
automatic FX
AI-created financial amounts
automatic routing activation / autonomous savings execution
hidden Provider retry after billable-possible state
Provider call directly from Backend bypassing Gateway governance
```

## Review result

```text
Unresolved design blockers = 0
TBD placeholders           = 0
TODO placeholders          = 0
Runtime code in M17 branch = 0
Migration files in M17     = 0
```

This matrix records design completeness only. Runtime tests do not exist yet and are M18 work; no M18 implementation PASS is claimed by M17.
