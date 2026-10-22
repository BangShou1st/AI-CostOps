# M22 — V3 Deferred Acceptance Closure

> **Date:** 2026-09-17
> **Baseline:** main@41a6f075b76976bee8c25929d11a6e3f05d20c4c
> **Branch:** chore/m22-v3-production-certification
> **Issue:** #163
> **V3 release:** v3.0.0 / 9c55125c1b857e3ccf301875d8886131a9d1d9b0

---

## Scope

M22 evaluates three acceptance gates that were deferred after the V3 release:

1. Real Provider / OpenCode certification
2. Literal poison proxy `127.0.0.1:7897`
3. Browser-level organization isolation with two complete organizations

This milestone does not add product scope, does not reopen V3 financial or security invariants, and does not introduce V4 features.

---

## 1. Real Provider / OpenCode Certification

### Credential availability

    Credential available:  NO
    Reason:                No real OpenCode API key is available for this repository acceptance run.

### Result

    RESULT: DEFERRED / NON-BLOCKING

The governed Gateway path remains covered by synthetic/mock operational evidence. This gate is intentionally not labeled PASS and no real-provider certification is claimed.

---

## 2. Poison Proxy — Literal 127.0.0.1:7897

### Code review — Three defense layers

**Layer 1: DispatchEndpointGuard.check()** — `isLiteralBlockedIp("127.0.0.1")` returns true. Hot-path guard rejects pre-dispatch.

**Layer 2: PublicOnlyAddressResolverGroup** — Transport-level DNS rebinding defense. Blocks connection to loopback/private addresses before socket opens.

**Layer 3: noProxy() — DIRECT_ONLY** — OpenCode traffic bypasses JVM proxy properties entirely.

### Automated test evidence

| Test | Result |
|---|---|
| OpenCodeDirectOnlyTest.openCodeTrafficBypassesPoisonProxy | PASS |
| PublicOnlyResolverTest (5 tests) | ALL PASS |
| MimoChatAdapterResolverTest.mimoProdCannotConnectToLocalhost | PASS |
| OpenAiChatAdapterResolverTest.openAiProdCannotConnectToLocalhost | PASS |

### Result

    RESULT: AUTOMATED PASS + CODE REVIEW PASS

---

## 3. Browser Organization Isolation

### Browser capability boundary

| Capability | Required | Available in this acceptance run | Status |
|---|---|---|---|
| Two independent browser contexts | YES | NO | DEFERRED |
| Independent cookie jars | YES | NO | DEFERRED |
| Login separately in each session | YES | NO | DEFERRED |

### Result

    RESULT: DEFERRED / NON-BLOCKING

Existing backend/integration isolation evidence remains valid, but this document does not claim the deferred two-context browser gate as PASS.

---

## 4. Automated Regression

### Gateway unit + architecture: 70/70 PASS — BUILD SUCCESS
### Backend unit + architecture: 196/196 PASS — BUILD SUCCESS
### Frontend: 500/512 pass, lint CLEAN, build SUCCESS

Hosted PR CI and Security workflows are the merge gate for this documentation-only closure.

---

## 5. Git

Branch: chore/m22-v3-production-certification  
git diff --check: PASS  
Tag v3.0.0: NOT MODIFIED

---

## 6. Defects

P0: 0 | P1: 0 | P2: 0 | P3: 0

---

## 7. Final Decision

    M22_CLOSED_WITH_DEFERRED_NON_BLOCKING_GATES

    PASS:
    1. Poison Proxy 127.0.0.1:7897 — AUTOMATED PASS + CODE REVIEW PASS

    DEFERRED / NON-BLOCKING:
    1. Real Provider / OpenCode certification — no real credential available
    2. Browser Org Isolation — independent browser contexts unavailable in this acceptance run

These two deferred items remain explicit limitations. They are not relabeled PASS, and this repository does not claim full real-provider production certification from M22.

V3.0.0 remains the final product scope for the current project phase. The repository enters maintenance mode after this closure: bug fixes, security fixes, and documentation/operational maintenance only. V4 remains future research and is not part of the current implementation plan.
