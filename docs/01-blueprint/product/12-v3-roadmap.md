# 12. V3 Roadmap — Model Provider Hub & Cost Intelligence

> Status: **M17 DESIGN FREEZE IN PROGRESS**  
> V2 stable release: `v2.0.0`  
> V2 frozen baseline: `main@93e359687c2d0e81b096d7a73cc8e04e16a7ecbe`  
> V3 design issue: #153  
> V3 master design: `docs/superpowers/specs/2026-09-10-m17-v3-model-provider-hub-cost-intelligence-design.md`

## Current product state

```text
V1       = RELEASED / FROZEN
V1.1     = RELEASED / FROZEN
V2       = RELEASED / FROZEN
v2.0.0   = RELEASED
M16      = CLOSED

V3       = DESIGN / DISCOVERY
M17      = CURRENT
Target   = v3.0.0
```

V3 starts only from the released V2 baseline. It does not reopen V2 financial invariants or rewrite V1–V23 migrations.

---

# V3 Product Thesis

V2 answers:

> Where did AI spend happen, who caused it, was it allowed, how was it metered/settled, and does it reconcile to Provider truth?

V3 adds:

> Which model connections can the organization govern, where is spend behaving abnormally, what is likely to happen next, and where can the organization save money without surrendering financial truth to an LLM?

Two pillars:

```text
A. Model Provider Hub
   - OpenCode Zen built-in template
   - custom OpenAI-compatible model connections
   - model discovery / probe / promotion
   - secure credentials
   - pricing / routing readiness

B. Cost Intelligence
   - anomaly detection
   - forecast
   - budget risk
   - deterministic savings recommendations
   - optional governed AI Advisor explanations
```

---

# M17 — V3 Detailed Design & Scope Freeze

## Goal

Freeze product and architecture before implementation.

## Deliverables

```text
Provider Hub domain model
OpenCode template policy
Custom Provider boundary
Network / SSRF / secret policy
Organization-private model catalog design
Connection/model lifecycle
Pricing/routing integration
Cost Intelligence algorithms
AI Advisor evidence/execution boundary
Permissions and audit
Backend API surface
Frontend contract-freeze boundary
Acceptance matrix
M18–M20 delivery plan
```

## Rule

M17 is documentation/design only. No V3 runtime implementation.

---

# M18 — V3 Backend Complete

## Goal

Implement the entire V3 backend and freeze its OpenAPI contract before frontend development begins.

## Provider Hub

```text
V24+ schema migration
versioned Provider Connection profiles
OpenCode Zen built-in template
DIRECT_ONLY OpenCode transport
poison-proxy/7897 bypass proof
custom OpenAI-compatible Chat Completions
credential reuse / rotation
SSRF protection
model discovery
manual model registration
model probe / verified capabilities
organization-private model catalog
model promotion
Pricing readiness
Routing readiness
Gateway connection-version lineage
```

## Cost Intelligence

```text
cost series queries
median / MAD anomaly detection
contribution drivers
Damped Holt forecast
run-rate fallback
budget-risk projection
counterfactual savings engine
recommendation lifecycle
currency isolation
exact BigDecimal financial calculations
```

## AI Advisor

```text
Advisor profile
AI_ADVISOR_USE / AI_ADVISOR_MANAGE
evidence envelope
internal governed execution identity
Gateway-based Provider execution
Advisor usage Metering / Settlement / Ledger
no hidden retry
failure isolation
self-cost feedback-loop protection
```

## M18 exit gate

```text
backend unit/integration/security PASS
OpenAPI PASS
Provider Hub security PASS
Cost Intelligence golden fixtures PASS
Advisor financial-governance PASS
BACKEND CONTRACT FREEZE
```

No frontend feature implementation begins before this gate.

---

# M19 — Product Design + Frontend

## Goal

Build a product-quality configuration/intelligence experience on the frozen M18 API.

## Product Design workflow

```text
1. Load current AI-CostOps UI as visual/product context.
2. Explore exactly three materially different visual directions.
3. Select one direction before production frontend implementation.
4. Implement Provider Gallery / Connection Wizard / Provider Detail.
5. Implement Cost Intelligence / AI Advisor views.
6. Run design QA against selected visual direction.
7. Run Browser E2E + responsive + keyboard/accessibility acceptance.
```

## Planned information architecture

```text
AI / Intelligence
  Cost Intelligence
    Overview
    Anomalies
    Forecast
    Savings
  AI Advisor

Settings
  Model Providers
    Provider Gallery
    Connections
    Models
  Model Pricing
  Routing Policies
```

OpenCode should be a short guided connection experience. Custom Providers use a wizard, not a raw low-level configuration form.

---

# M20 — V3 Production Acceptance

## Goal

Prove V3 preserves V1/V2 correctness while adding Provider Hub and AI intelligence safely.

## Required gates

```text
A. Financial invariants
B. Provider Hub security / isolation
C. OpenCode DIRECT_ONLY / zero proxy hit
D. SSRF protections
E. Cost Intelligence deterministic fixtures
F. AI Advisor cannot author financial truth
G. Advisor cost is governed / metered / settled
H. Browser/product acceptance
I. Hosted CI / security
```

Real Provider certification is used when free/non-paid evidence is available. A paid external credential is not required merely to release this personal project. Any unavailable real-provider gate is explicitly marked deferred/non-PASS; Mock evidence is never relabeled as real Provider evidence.

Target release:

```text
v3.0.0
```

---

# Frozen V3.0 Non-goals

```text
Responses API
Anthropic Messages
Gemini-native adapter
Embedding
MCP / agent tools
arbitrary Header JSON
browser-configurable proxy
private/local model endpoints by default
AI auto-routing / auto-apply recommendation
training / fine-tuning
Python ML service
SAML / SCIM
ERP / GL
Full FOCUS
Automatic FX
multi-region
Kafka / Elasticsearch / Kubernetes without measured need
```

---

# V3 Success Definition

V3 is successful when a user can:

```text
connect OpenCode Zen safely
or connect a custom OpenAI-compatible Provider
        ↓
discover/register and verify a model
        ↓
configure exact Pricing + Routing
        ↓
run governed AI traffic through existing Gateway truth
        ↓
see abnormal spend and future budget risk
        ↓
receive exact, reproducible savings calculations
        ↓
optionally ask AI Advisor to explain those facts
        ↓
see the Advisor's own AI cost governed by AI-CostOps
```

And the system still proves:

```text
LLM financial authority = 0
Provider secret leak = 0
cross-org model leak = 0
SSRF escape = 0
OpenCode proxy hit = 0
lost Settlement = 0
duplicate Ledger effect = 0
race Budget overspend = 0
```
