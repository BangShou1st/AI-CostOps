# 13. M19 V3 Frontend Experience Spec (hybrid A+C+B)

Issue #157. Branch feat/m19-v3-frontend-experience. Baseline main@ac107cb (M18 freeze). Active, direction frozen.

## 1. Direction freeze

A = skeleton and visual system (Premium FinOps Command Center).
C = Intelligence reading and Advisor explanation (Editorial Intelligence Workspace).
B = Provider, Pricing, Routing governance density (Cloud Control Plane).
One shared token, type, spacing, status, icon, interaction language.

## 2. IA (frozen)

AI Intelligence: Cost Intelligence Overview, Anomalies, Forecast, Savings; AI Advisor.
Settings: Model Providers Gallery, Connections, Models; Model Pricing; Routing Policies.
V1 Ledger and V2 Governance stay usable. V3 adds, never reshuffles old IA.

## 3. Tokens (A baseline, shared by all pages)

Page bg #eef1f6. Surface #ffffff. Sunken #f7f9fc.
Border #e2e7f0, strong #cbd4e3.
Text primary #172033, secondary #475569, muted #8494ab.
Semantic success #1a7f4b, warning #b45309, danger #b42318, info #1d4ed8.
Financial positive and negative reuse semantic pair, never color-only.
Chart order: actual #1d4ed8 solid, forecast #8494ab dashed, budget #172033 thin,
risk-threshold #b45309 dotted, savings #1a7f4b.
Radius 10 cards, 8 controls, 6 pills. Shadow on floating layers only.
Type Inter system. H1 26px 800. Section 15px 750. Body 14px 1.55. Eyebrow 11px 800 0.14em.
Numbers tabular-nums. Spacing 4 8 12 16 20 24 32 48. Page max 1440, padding 24, mobile 16.
Status always text plus icon plus treatment. Keyboard and SR readable.

## 4. V3 shell

Keep dark rail plus mobile drawer. Add Intelligence and Provider Hub groups.
Overview briefing flow: Are we okay, What changed, What will happen, What can I do.
No KPI card wall. One answer strip, one main figure, one exposure rail, then drivers,
anomalies, savings in priority order.

## 5. Intelligence (A precision plus C narrative)

Overview: answer strip, actual vs forecast vs budget vs threshold figure with axis,
tooltip, currency, timezone, legend, textual summary. Exposure rail immediate vs
projected period end. Top drivers. Anomalies needing attention. Savings pulse.
Skeleton mirrors real layout, never bare spinner.
Anomalies carry severity, materiality, baseline, observed, delta, driver, time, scope, evidence.
Forecast shows DAMPED_HOLT or RECENT_RUN_RATE plus method, confidence, buckets, observedThrough.
Savings show current vs candidate, replay, amount plus percent, routingChangeRequired link,
pricing evidence, readiness. CTA Review, Acknowledge, Dismiss, Mark Applied, Inspect Routing.
Lifecycle OPEN ACKNOWLEDGED DISMISSED APPLIED EXPIRED as text plus icon plus treatment.

## 6. AI Advisor (explanation, never chat clone)

Order: Verified financial facts, AI-generated explanation, Drivers, Recommended actions,
Warnings, Fact references. Label states AI did not compute money.
Job states PENDING CLAIMED DISPATCHING RUNNING COMPLETED FAILED plus retry stepper.
Right dock or evidence drawer allowed when structure serves explanation.

## 7. Provider Hub (B density on A system)

Gallery is a readiness matrix: connection, models, pricing, routing. Not a logo wall.
OpenCode Zen featured as built-in. Custom rows use neutral lettermark.
Connections: Connection Auth Endpoint Network policy Version Health Last probe Status.
Secrets always masked labels.
Models: AVAILABLE UNAVAILABLE, protocol Chat Unsupported Unknown, probe PASS FAIL UNKNOWN,
pricing Ready Missing Verified-free Unknown, routing Eligible Not eligible.
Pricing: version currency dimensions unit-qty unit-price decimal-string effective
active draft retired. VERIFIED_FREE metadata is not ACTIVE zero-price truth.
Routing: logical model candidate priority weight readiness pricing budget enforcement.
Savings routingChangeRequired links here for human governance. No auto-routing implication.
Separations enforced in copy and visuals: healthy connection is not routing ready,
AVAILABLE is not Chat compatible, VERIFIED_FREE is not zero-price truth,
recommendation is not automatic routing.

## 8. Numbers and dates

Money decimal-string end to end, Intl per currency. Compact only secondary with exact
value in tooltip or detail. Uniform percent precision. No mixed compact forms per screen.
Frontend never recomputes authoritative money.
Dates distinguish period, effective, generated, updated, last-probe. Explicit timezone.

## 9. States, responsive, a11y, motion

Every major page: loading skeleton, empty, partial, error, denied, not-configured,
stale provider. Skeleton matches real layout.
Desktop first 1440 and 1280, 1024 intact, 390 simplified never broken. No global h-overflow.
Tables degrade via contained scroll, column priority, detail drawer, mobile cards.
Headings, keyboard, visible focus, named buttons, labeled icons and forms, contrast,
chart text fallback, reduced motion. Existing icon library only, no emoji UI.
Motion subtle fast functional only.

## 10. Data honesty and testing

Real M18 contract only. No invented endpoints. No silent prod mock. No frontend money truth.
Screenshots may use deterministic M18-schema fixtures. Prod code stays real-client only.
Per feature: unit plus API-state plus interaction plus Playwright E2E.
npm test, lint, build, browser E2E green. Visual regression on key screens for layout,
major states, hierarchy. Not brittle pixel-perfect.

## 11. Build order

C0 spec plus tokens. C1 shell plus Overview with 1440 1280 1024 390 and 2 Impeccable passes.
C2 Anomalies Forecast Savings. C3 Advisor. C4 Provider Hub. C5 Pricing Routing.
C6 responsive a11y E2E visual acceptance plus Sol review. No full-M19 single shot.
Each screen reports implemented, screenshot, findings, changes, concerns. No merge without Sol.
