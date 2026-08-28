# M9 Browser E2E Evidence — AIC-078

> Recorded from a real local execution on the implementation SHA. The `browser-e2e`
> CI check is defined in this PR; its first run result is appended below after the
> PR is pushed.

## Scope

Automate the V1 critical human-browser acceptance paths as deterministic Chromium
E2E with Playwright Test, without replacing the existing backend integration suite.

- Branch: `test/m9-browser-e2e`
- Implementation SHA: `e6fc697` (`test(e2e): automate critical browser flows`)
- Evidence SHA: the commit that adds this document.

## Toolchain

| Item | Value |
|---|---|
| Playwright Test | `1.62.1` (exact, dev dependency) |
| Browser | Chromium `151.0.7922.34` (Playwright chromium v1234, `playwright-core` build 1234) |
| Node | `v24.14.0` |
| Runner mode | `workers: 1`, `fullyParallel: false`, `retries: 0` |

## Spec / scenario matrix

| Spec file | Browser-visible coverage | Result |
|---|---|---|
| `auth-session.spec.ts` | login → session restore after full reload (refresh cookie) → logout → anonymous redirect → expired-cookie redirect | PASS |
| `authorization-negative.spec.ts` | register EMPLOYEE via UI → wrong-role user sees no finance nav and direct URLs render the 403 page; allowed surface still works | PASS |
| `budget-expense-ledger.spec.ts` | budget create → commitment request → approve (已生效) → expense create/evidence/submit → review approve → manual allocation → post (已记账) → ledger posting/entry → correction | PASS |
| `import-allocation.spec.ts` | upload DeepSeek ZIP → worker READY_FOR_REVIEW → confirm (已确认) → allocate every canonical charge via costs page → CONFIRMED decision persisted | PASS |
| `reconciliation-close.spec.ts` | run reconciliation (已完成) → resolve open cases via case UI → close period (已关闭) → CLOSED-period budget write rejected (账期未开放) → reopen (开放) | PASS |

- Total: **5 specs / 5 scenarios — 5 passed / 0 failed**
- Local runtime (full suite, warm stack): **21.7 s**

## Determinism controls

- No arbitrary sleeps: the specs use only locator assertions, `expect.poll`, URL
  synchronization and response/state waits; `grep -r waitForTimeout e2e` returns nothing.
- Specs share one full Compose stack, so the runner is strictly serial.
- A `page.reload()` is used after resolving a reconciliation case because the
  run-detail case table is cached by react-query and would otherwise keep showing
  a stale 待处理 tag (a synchronization step, not a timing wait).

## Local reproduction

```powershell
Set-Location "E:\AI-CostOps"
# Start an isolated full Compose project with a synthetic CI-only env, e.g.:
docker compose -p aicostops-browser-e2e --env-file .e2e-ci.env up -d --build --wait --wait-timeout 360

Set-Location "E:\AI-CostOps\frontend"
npm ci
npx playwright install chromium
$env:AICOSTOPS_E2E_BASE_URL = "http://localhost:8080"
$env:AICOSTOPS_E2E_ADMIN_EMAIL = "admin@example.test"
$env:AICOSTOPS_E2E_ADMIN_PASSWORD = "<synthetic CI-only bootstrap password>"
npm run test:e2e
```

The E2E environment is CI-only and synthetic: the dev bootstrap identity and every
fixture value (DeepSeek CSV rows, receipts, passwords) are fake and contain a
`sk-SECRET-SENTINEL-DO-NOT-PERSIST` marker where a key-shaped value is required.

## Verification gates (all exit 0)

```powershell
cd frontend
npm run lint            # eslint clean (incl. e2e/)
npm test -- --run       # 47 files, 432 tests passed (e2e excluded from vitest)
npm run build           # tsc -b && vite build OK
npm run test:e2e        # 5/5 passed (21.7 s)
```

`vite.config.ts` gained `test.exclude: ['e2e/**', ...]` so vitest never treats the
Playwright specs as jsdom unit tests.

## CI job: `browser-e2e`

Added to `.github/workflows/ci.yml`:

- checkout → writes a synthetic CI-only `.e2e-ci.env` → `docker compose -p aicostops-browser-e2e up -d --build --wait --wait-timeout 360` → `npm ci` → `npx playwright install --with-deps chromium` → `npm run test:e2e`.
- Failure artifacts: uploads `playwright-report/`, `test-results/` (traces, screenshots, videos) and the JUnit XML only `if: failure()`; no secrets are uploaded.
- Cleanup: `down --volumes --remove-orphans` on the isolated `aicostops-browser-e2e` project in an `always()` step; a fresh runner's volumes are its own, so this never touches a developer stack.
- The login rate guard is raised only inside the E2E/CI environment
  (`AICOSTOPS_LOGIN_ACCOUNT_LIMIT=500`) because a full E2E pass performs many
  syntactically identical logins; production defaults are unchanged.

CI run link: filled in after this PR's first run (no fabricated link recorded).

## Known limitations

- The five specs intentionally share one Compose stack and one organization's
  books; the Playwright runner must stay at `workers: 1`.
- The reconciliation-close spec mutates period state (close → reopen). On a
  failure between those two steps a local developer stack can be left with a
  CLOSED period; CI always starts from a fresh stack, and locally the fix is
  `docker compose -p <e2e project> down -v`.
- Public registration is enabled in the E2E environment so the wrong-role
  employee can be registered through the UI (dev-only policy).
- antd v6 renders two-CJK-character buttons with an inserted space (e.g. `创 建`);
  the E2E selectors use whitespace-tolerant regex names for such buttons.
- API helpers create only tenant-level prerequisites (provider account, project,
  period lookup); every behavior under test is driven through the browser.