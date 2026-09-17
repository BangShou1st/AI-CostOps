# M22 — V3 Deferred Acceptance & Production Certification

> **Date:** 2026-09-17
> **Baseline:** main@41a6f075b76976bee8c25929d11a6e3f05d20c4c
> **Branch:** chore/m22-v3-production-certification
> **Issue:** #163
> **V3 release:** v3.0.0 / 9c55125c1b857e3ccf301875d8886131a9d1d9b0

---

## Scope

M22 closes three previously DEFERRED gates:

1. Real Provider / OpenCode certification
2. Literal poison proxy 127.0.0.1:7897
3. Browser-level organization isolation (two complete organizations)

---

## 1. Real Provider / OpenCode Certification

### Credential availability

    Credential available:  NO
    Reason:                No real OpenCode API key provided by owner.

### Result

    RESULT: BLOCKED

---

## 2. Poison Proxy — Literal 127.0.0.1:7897

### Code review — Three defense layers

**Layer 1: DispatchEndpointGuard.check()** — isLiteralBlockedIp("127.0.0.1") returns true. Hot-path guard rejects pre-dispatch.

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

### Browser skill capability check

| Capability | Required | Actual | Status |
|---|---|---|---|
| Two independent browser contexts | YES | NO | BLOCKED |
| Independent cookie jars | YES | NO | BLOCKED |
| Login separately in each session | YES | NO | BLOCKED |

### Result

    RESULT: BLOCKED

---

## 4. Automated Regression

### Gateway unit + architecture: 70/70 PASS — BUILD SUCCESS
### Backend unit + architecture: 196/196 PASS — BUILD SUCCESS
### Frontend: 500/512 pass, lint CLEAN, build SUCCESS

---

## 5. Git

Branch: chore/m22-v3-production-certification
git diff --check: PASS
Tag v3.0.0: NOT MODIFIED
Merge: NOT PERFORMED

---

## 6. Defects

P0: 0 | P1: 0 | P2: 0 | P3: 0

---

## 7. Final Decision

    M22_BLOCKED

    Two gates remain BLOCKED:
    1. Real Provider/OpenCode — no credential
    2. Browser Org Isolation — no independent contexts

    One gate is PASS:
    1. Poison Proxy 127.0.0.1:7897 — AUTOMATED PASS + CODE REVIEW PASS

    Owner must provide:
    1. OpenCode API key for Real Provider certification
    2. Browser tool with independent context support for org isolation

Awaiting: owner decision on prerequisites or DEFERRED status.

No v3.0.0 tag, release, or merge was performed.
