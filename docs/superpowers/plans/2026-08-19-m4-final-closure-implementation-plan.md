# M4 Final Closure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close M4 issues #80, #82, and #84 in one final branch by fixing anonymous bootstrap UX, role-label clipping, and the missing browser Budget-create flow backed by a minimal BillingPeriod list API.

**Architecture:** Preserve the cross-tab auth architecture merged in #83 and only distinguish initial anonymous bootstrap from runtime terminal invalidation. Add a read-only organization-scoped BillingPeriod list endpoint following existing application-layer authorization patterns, then extend the existing Budget list page with a permission-gated create modal that consumes that endpoint and the already-existing Budget POST contract. Keep all financial truth server-authoritative and all money values as exact decimal strings.

**Tech Stack:** Java 21, Spring Boot, MyBatis, MySQL/Testcontainers integration tests, React, TypeScript, TanStack Query, Ant Design, Vitest/Testing Library.

**Spec:** `docs/superpowers/specs/2026-08-19-m4-final-closure-design.md`

## Global Constraints

- Base is `main@27b873193b66b74b7b23f53904128e3fe0f9ce05`; work only on `fix/m4-final-closure`.
- Close only #80, #82, and #84; do not add M5 / AIC-047+ work.
- Do not redesign cross-tab auth, backend auth, Redis rotation/replay, or token storage.
- BillingPeriod API is read-only: one organization-scoped list endpoint only; no create/detail/close/reopen and no Flyway/schema change.
- Budget financial truth remains backend authoritative; never recompute `availableAmount` or `overBudget` in JavaScript.
- Money remains exact decimal strings end-to-end; no `Number`, `parseFloat`, `parseInt` for financial amounts, or `toFixed` financial normalization.
- Role IDs/codes, permission mappings, authorization payloads, and backend role behavior do not change.
- `.zcode/` and `start-dev.bat` remain untracked/uncommitted.
- Use PowerShell-compatible commands in validation reports.

---

### Task 1: Fix initial anonymous bootstrap warning semantics (#84)

**Files:**
- Modify: `frontend/src/features/auth/AuthSessionProvider.tsx`
- Modify: `frontend/src/features/auth/AuthSessionProvider.test.tsx`

**Interfaces:**
- Consumes: existing `bootstrapSession(...)`, `isTerminalSessionError(...)`, `clearLocalSession()`, `handleLocalTerminal()`.
- Produces: initial mount `AUTH_SESSION_EXPIRED` settles to anonymous silently; runtime `authEvents` / logout terminal failures remain unchanged.

- [ ] **Step 1: Add the failing regression test**

Add this focused test inside the existing `AuthSessionProvider session expiry` suite:

```tsx
it('anonymousBootstrapExpiryIsSilent', async () => {
  const coordinator = createTestCoordinator()
  mockedAuthApi.refresh.mockRejectedValue(authError('AUTH_SESSION_EXPIRED'))

  renderProvider(<SessionActionsProbe />, coordinator)

  await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('anonymous'))
  expect(accessTokenStore.get()).toBeNull()
  expect(mockedAuthApi.me).not.toHaveBeenCalled()
  expect(coordinator.publish).not.toHaveBeenCalled()
  expect(message.warning).not.toHaveBeenCalled()
})
```

Do not weaken existing tests such as `terminalSessionFailurePropagatesAcrossTabs` or logout terminal propagation.

- [ ] **Step 2: Run the focused test and verify RED**

```powershell
Set-Location frontend
npm test -- --run src/features/auth/AuthSessionProvider.test.tsx
```

Expected before the fix: the new test fails because initial bootstrap routes terminal 401 through `handleLocalTerminal()` and calls `message.warning` / publishes invalidation.

- [ ] **Step 3: Make the smallest provider change**

In the initial mount `useEffect` bootstrap catch, keep `RefreshRaceUnresolvedError` guidance, but do **not** route initial `AUTH_SESSION_EXPIRED` / `AUTH_REFRESH_REPLAY` through `handleLocalTerminal()`.

The initial bootstrap catch should end by establishing anonymous local projection only, for example:

```tsx
.catch((error: unknown) => {
  if (!isCurrentLifecycle(epoch)) return
  if (error instanceof AuthLifecycleSupersededError) return
  if (error instanceof RefreshRaceUnresolvedError) {
    message.warning('会话刷新冲突暂未解决，请稍后刷新页面重试。')
  }
  clearLocalSession()
})
```

Do not change `handleLocalTerminal`, the `authEvents` subscription, remote `SESSION_INVALIDATED`, login, logout, or cross-tab coordinator behavior.

- [ ] **Step 4: Run auth tests and verify GREEN**

```powershell
npm test -- --run src/features/auth/AuthSessionProvider.test.tsx src/features/auth/authSession.test.ts src/features/auth/crossTabAuthCoordinator.test.ts
```

Expected: all focused auth tests pass; the new anonymous bootstrap test passes; existing runtime invalidation tests still pass.

- [ ] **Step 5: Commit Task 1**

```powershell
Set-Location ..
git add frontend/src/features/auth/AuthSessionProvider.tsx frontend/src/features/auth/AuthSessionProvider.test.tsx
git commit -m "fix(auth): silence anonymous bootstrap expiry"
```

---

### Task 2: Make role assignment labels fully readable (#80)

**Files:**
- Modify: `frontend/src/features/settings/users/UsersPage.tsx`
- Modify: `frontend/src/features/settings/users/UsersPage.test.tsx`

**Interfaces:**
- Consumes: existing `roleLabel(role.code)` and `settingsApi.createRoleAssignment(memberId, roleId, scopeType, scopeId)`.
- Produces: full-width role Select/popup presentation while preserving the selected role ID submitted to the backend.

- [ ] **Step 1: Add a focused role assignment regression test**

Extend the UsersPage test suite with a real long role option and assert both presentation and payload identity:

```tsx
it('roleAssignmentShowsFullLocalizedRoleLabelAndSubmitsRoleId', async () => {
  mockedSettingsApi.listRoles.mockResolvedValue([
    { id: 'finance-admin-id', code: 'FINANCE_ADMIN', name: 'Finance admin', permissions: [] },
  ])
  mockedSettingsApi.createRoleAssignment.mockResolvedValue({} as never)
  renderUsersPage(['USER_READ', 'ROLE_ASSIGN'])

  fireEvent.click(await screen.findByRole('button', { name: /分配角色/i }))
  fireEvent.click(await screen.findByRole('button', { name: /添加角色分配/i }))

  const roleCombobox = screen.getAllByRole('combobox')[0]
  expect(roleCombobox.closest('.ant-select')).toHaveStyle({ width: '100%' })

  fireEvent.mouseDown(roleCombobox)
  fireEvent.click(await screen.findByText('财务管理员（FINANCE_ADMIN）'))

  const scopeCombobox = screen.getAllByRole('combobox')[1]
  fireEvent.mouseDown(scopeCombobox)
  fireEvent.click(await screen.findByText('ORG'))
  fireEvent.change(screen.getByPlaceholderText('组织、项目、团队或成本中心 ID'), { target: { value: '2' } })
  fireEvent.click(screen.getByRole('button', { name: '分配' }))

  await waitFor(() => {
    expect(mockedSettingsApi.createRoleAssignment)
      .toHaveBeenCalledWith('11', 'finance-admin-id', 'ORG', '2')
  })
})
```

If Ant Design renders multiple hidden comboboxes in jsdom, scope the queries to the open `添加角色分配` modal with Testing Library `within(...)`; do not weaken the semantic assertions.

- [ ] **Step 2: Run the focused test and verify RED**

```powershell
Set-Location frontend
npm test -- --run src/features/settings/users/UsersPage.test.tsx
```

Expected before the fix: the width assertion fails because the role Select has no full-width presentation rule.

- [ ] **Step 3: Apply the minimal presentation fix**

In `AssignRoleModal`, make the role Select use the modal width rather than intrinsic content width:

```tsx
<Select
  style={{ width: '100%' }}
  value={roleId}
  placeholder="选择角色"
  options={(rolesQuery.data ?? []).map((role: Role) => ({
    value: role.id,
    label: roleLabel(role.code),
  }))}
  loading={rolesQuery.isLoading}
  onChange={(value) => { setRoleId(value); setScopeType(undefined) }}
/>
```

Apply `style={{ width: '100%' }}` to the scope Select in the same modal for consistent responsive layout if needed, but do not introduce page-level fixed widths or change role labels/codes.

- [ ] **Step 4: Run settings tests and verify GREEN**

```powershell
npm test -- --run src/features/settings/users/UsersPage.test.tsx
```

Expected: focused settings tests pass and submitted role ID remains `finance-admin-id`.

- [ ] **Step 5: Commit Task 2**

```powershell
Set-Location ..
git add frontend/src/features/settings/users/UsersPage.tsx frontend/src/features/settings/users/UsersPage.test.tsx
git commit -m "fix(settings): show full role assignment labels"
```

---

### Task 3: Add the minimal BillingPeriod read API (#82 backend)

**Files:**
- Modify: `backend/src/main/java/com/aicostops/budget/infrastructure/BillingPeriodMapper.java`
- Create: `backend/src/main/java/com/aicostops/budget/application/BillingPeriodQueryService.java`
- Create: `backend/src/main/java/com/aicostops/budget/api/BillingPeriodResponses.java`
- Create: `backend/src/main/java/com/aicostops/budget/api/BillingPeriodController.java`
- Create: `backend/src/test/java/com/aicostops/budget/BillingPeriodApiIntegrationTest.java`
- Modify: `docs/02-development/api/openapi.yaml`

**Interfaces:**
- Produces backend endpoint: `GET /api/v1/billing-periods`.
- Produces response item shape:

```json
{
  "id": "123",
  "periodStart": "2026-08-01T00:00:00Z",
  "periodEnd": "2026-09-01T00:00:00Z",
  "status": "OPEN",
  "version": 0
}
```

- Authorization: current organization context + organization-level `BUDGET_READ`; no foreign organization row may be returned.
- Ordering: `period_start DESC, id DESC`.

- [ ] **Step 1: Write the integration test first**

Create `BillingPeriodApiIntegrationTest` extending `BudgetTestSupport`:

```java
@SpringBootTest(properties =
        "aicostops.auth.jwt-signing-secret=billing-period-api-test-only-signing-secret-with-more-than-32-bytes")
@AutoConfigureMockMvc
@Tag("integration")
class BillingPeriodApiIntegrationTest extends BudgetTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listReturnsOnlyCurrentOrganizationPeriodsInDeterministicOrder() throws Exception {
        var newer = insertBillingPeriod(orgId,
                "2026-09-01 00:00:00.000000", "2026-10-01 00:00:00.000000", "CLOSING");
        insertBillingPeriod(foreignOrgId,
                "2026-10-01 00:00:00.000000", "2026-11-01 00:00:00.000000", "OPEN");

        mockMvc.perform(get("/api/v1/billing-periods")
                        .header("Authorization", readerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(Long.toString(newer)))
                .andExpect(jsonPath("$[0].status").value("CLOSING"))
                .andExpect(jsonPath("$[0].periodStart").isString())
                .andExpect(jsonPath("$[0].periodEnd").isString())
                .andExpect(jsonPath("$[0].version").isNumber())
                .andExpect(jsonPath("$[*].id",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(Long.toString(foreignOrgId)))));
    }

    @Test
    void listRequiresOrganizationBudgetRead() throws Exception {
        mockMvc.perform(get("/api/v1/billing-periods")
                        .header("Authorization", projectOwnerBearer()))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/billing-periods"))
                .andExpect(status().isUnauthorized());
    }
}
```

For the foreign-row assertion, capture the returned foreign **period id** in a local variable and assert that id is absent; do not compare against `foreignOrgId`.

- [ ] **Step 2: Run the new integration test and verify RED**

```powershell
Set-Location backend
.\mvnw.cmd -Dtest=BillingPeriodApiIntegrationTest test
```

Expected: RED because `/api/v1/billing-periods` does not exist.

- [ ] **Step 3: Add the mapper list query**

Append to `BillingPeriodMapper`:

```java
@Select("""
        SELECT
        """ + PERIOD_COLUMNS + """
        FROM billing_period bp
        WHERE bp.org_id=#{organizationId}
        ORDER BY bp.period_start DESC, bp.id DESC
        """)
List<BillingPeriod> selectByOrganization(@Param("organizationId") long organizationId);
```

No new table/index/migration.

- [ ] **Step 4: Add application-layer authorization**

Create `BillingPeriodQueryService` using the same auth context machinery as Budget services:

```java
@Service
public class BillingPeriodQueryService {
    private static final String PERMISSION_BUDGET_READ = "BUDGET_READ";

    private final AuthorizationContextService authorizationContexts;
    private final M1AuthorizationService authorization = new M1AuthorizationService();
    private final BillingPeriodMapper mapper;

    public BillingPeriodQueryService(
            AuthorizationContextService authorizationContexts,
            BillingPeriodMapper mapper) {
        this.authorizationContexts = authorizationContexts;
        this.mapper = mapper;
    }

    public List<BillingPeriod> list(AuthenticatedUser user) {
        var context = authorizationContexts.current(user);
        authorization.requireOrg(context, PERMISSION_BUDGET_READ);
        return mapper.selectByOrganization(context.organizationId());
    }
}
```

This intentionally requires an ORG-level Budget read grant because returning all periods is organization-wide metadata.

- [ ] **Step 5: Add the HTTP response and controller**

Create `BillingPeriodResponses`:

```java
public final class BillingPeriodResponses {
    private BillingPeriodResponses() {}

    public record BillingPeriodResponse(
            String id,
            Instant periodStart,
            Instant periodEnd,
            String status,
            long version) {
        public static BillingPeriodResponse from(BillingPeriod period) {
            return new BillingPeriodResponse(
                    Long.toString(period.id()),
                    period.periodStart(),
                    period.periodEnd(),
                    period.status().name(),
                    period.version());
        }
    }
}
```

Create controller:

```java
@RestController
@RequestMapping("/api/v1/billing-periods")
public class BillingPeriodController {
    private final BillingPeriodQueryService queries;

    public BillingPeriodController(BillingPeriodQueryService queries) {
        this.queries = queries;
    }

    @GetMapping
    public List<BillingPeriodResponse> list(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        return queries.list(authenticatedUser).stream()
                .map(BillingPeriodResponse::from)
                .toList();
    }
}
```

- [ ] **Step 6: Synchronize OpenAPI**

Add a `BillingPeriodResponse` schema with string `id`, ISO-8601 `periodStart` / `periodEnd`, enum status `OPEN | CLOSING | CLOSED`, and integer `version`. Add `GET /billing-periods` returning an array of those objects plus the existing Problem response for auth/authorization failures.

Do not add POST/PUT/close/reopen paths.

- [ ] **Step 7: Run backend focused tests and verify GREEN**

```powershell
.\mvnw.cmd -Dtest=BillingPeriodApiIntegrationTest,BudgetApiIntegrationTest test
```

Expected: both test classes pass.

- [ ] **Step 8: Commit Task 3**

```powershell
Set-Location ..
git add backend/src/main/java/com/aicostops/budget/infrastructure/BillingPeriodMapper.java `
        backend/src/main/java/com/aicostops/budget/application/BillingPeriodQueryService.java `
        backend/src/main/java/com/aicostops/budget/api/BillingPeriodResponses.java `
        backend/src/main/java/com/aicostops/budget/api/BillingPeriodController.java `
        backend/src/test/java/com/aicostops/budget/BillingPeriodApiIntegrationTest.java `
        docs/02-development/api/openapi.yaml
git commit -m "feat(budget): expose billing period list"
```

---

### Task 4: Add Budget Create to the existing browser workflow (#82 frontend)

**Files:**
- Modify: `frontend/src/features/budgets/api/budgetApi.ts`
- Create: `frontend/src/features/budgets/api/billingPeriodApi.ts`
- Modify: `frontend/src/features/budgets/BudgetsListPage.tsx`
- Modify: `frontend/src/features/budgets/BudgetPages.test.tsx`

**Interfaces:**
- Consumes: `GET /billing-periods` response from Task 3.
- Adds `budgetApi.create(body)` to the existing `POST /budgets` endpoint.
- Create body remains exactly:

```ts
interface CreateBudgetBody {
  billingPeriodId: string
  scopeType: BudgetScopeType
  scopeId: string
  currency: string
  totalAmount: string
}
```

- [ ] **Step 1: Extend API types/mocks and write failing UI tests**

In `BudgetPages.test.tsx`, mock `budgetApi.create` and the new BillingPeriod API module. Define:

```tsx
const BILLING_PERIODS = [
  {
    id: '3',
    periodStart: '2026-08-01T00:00:00Z',
    periodEnd: '2026-09-01T00:00:00Z',
    status: 'OPEN' as const,
    version: 0,
  },
]
```

Add at minimum these tests:

```tsx
it('hides budget create without BUDGET_MANAGE', async () => {
  renderPage(['BUDGET_READ'])
  await screen.findByRole('heading', { name: '预算' })
  expect(screen.queryByRole('button', { name: '创建预算' })).not.toBeInTheDocument()
})

it('creates budget from backend billing period options using exact decimal string', async () => {
  mockedBillingPeriodApi.list.mockResolvedValue(BILLING_PERIODS)
  mockedBudgetApi.create.mockResolvedValue(SENTINEL_BUDGET)
  renderPage(['BUDGET_READ', 'BUDGET_MANAGE'])

  fireEvent.click(await screen.findByRole('button', { name: '创建预算' }))
  expect(await screen.findByText(/2026-08-01T00:00:00Z.*2026-09-01T00:00:00Z/)).toBeInTheDocument()
  expect(screen.queryByLabelText(/账期 ID/i)).not.toBeInTheDocument()

  // Select the backend period and scope type, then enter the existing scope id,
  // currency, and exact 8-decimal money string.
  // Use within(dialog) to avoid unrelated comboboxes.

  await waitFor(() => {
    expect(mockedBudgetApi.create).toHaveBeenCalledWith({
      billingPeriodId: '3',
      scopeType: 'PROJECT',
      scopeId: '42',
      currency: 'CNY',
      totalAmount: '1000.00000000',
    })
  })
  expect(mockedBillingPeriodApi.list).toHaveBeenCalledTimes(1)
})
```

The completed test must also prove the budget list is invalidated/refetched after success by asserting `mockedBudgetApi.list` is called again.

- [ ] **Step 2: Run Budget tests and verify RED**

```powershell
Set-Location frontend
npm test -- --run src/features/budgets/BudgetPages.test.tsx
```

Expected: RED because create action/API/BillingPeriod API do not yet exist.

- [ ] **Step 3: Add Budget create API**

Extend `budgetApi.ts`:

```ts
export interface CreateBudgetBody {
  billingPeriodId: string
  scopeType: BudgetScopeType
  scopeId: string
  currency: string
  totalAmount: string
}

export const budgetApi = {
  // existing list/get/update...
  async create(body: CreateBudgetBody): Promise<BudgetResponse> {
    return (await apiClient.post<BudgetResponse>('/budgets', body)).data
  },
}
```

Do not normalize `totalAmount` numerically in this module.

- [ ] **Step 4: Add BillingPeriod API module**

Create `billingPeriodApi.ts`:

```ts
import { apiClient } from '../../auth/authApi'

export type BillingPeriodStatus = 'OPEN' | 'CLOSING' | 'CLOSED'

export interface BillingPeriodResponse {
  id: string
  periodStart: string
  periodEnd: string
  status: BillingPeriodStatus
  version: number
}

export const billingPeriodApi = {
  async list(): Promise<BillingPeriodResponse[]> {
    return (await apiClient.get<BillingPeriodResponse[]>('/billing-periods')).data
  },
}

export const billingPeriodKeys = {
  list: () => ['billing-period', 'list'] as const,
}
```

- [ ] **Step 5: Implement the permission-gated create modal in BudgetsListPage**

Import `useAuth`, `hasPermission`, `useMutation`, `Button`, `Modal`, `Select`, `Input`, and the new API module. Keep the create flow on `/budgets`; do not add a route.

Required state:

```tsx
const auth = useAuth()
const queryClient = useQueryClient()
const canManage = hasPermission(auth.user?.permissions, 'BUDGET_MANAGE')
const [createOpen, setCreateOpen] = useState(false)
const [billingPeriodId, setBillingPeriodId] = useState<string>()
const [scopeType, setScopeType] = useState<BudgetScopeType>()
const [scopeId, setScopeId] = useState('')
const [currency, setCurrency] = useState('CNY')
const [totalAmount, setTotalAmount] = useState('')
```

Load BillingPeriods only while the modal is relevant:

```tsx
const periods = useQuery({
  queryKey: billingPeriodKeys.list(),
  queryFn: () => billingPeriodApi.list(),
  enabled: canManage && createOpen,
})
```

Create mutation:

```tsx
const createBudget = useMutation({
  mutationFn: () => budgetApi.create({
    billingPeriodId: billingPeriodId!,
    scopeType: scopeType!,
    scopeId,
    currency: currency.toUpperCase(),
    totalAmount,
  }),
  retry: false,
  onSuccess: () => {
    void queryClient.invalidateQueries({ queryKey: budgetKeys.lists() })
    setCreateOpen(false)
  },
})
```

Do not use numeric conversion on `totalAmount`.

Header action:

```tsx
<header className="page-header">
  <h1>预算</h1>
  {canManage && <Button type="primary" onClick={() => setCreateOpen(true)}>创建预算</Button>}
</header>
```

Modal fields:

- `账期`: Select from backend `periods.data`, label `${periodStart} → ${periodEnd}（${status}）`; value is period `id`.
- `范围类型`: Select `ORG | PROJECT | TEAM | COST_CENTER`, using existing `BUDGET_SCOPE_LABEL` in labels.
- `范围 ID`: existing string input; backend remains authoritative for scope validity.
- `币种`: string input, `maxLength={3}`.
- `总额`: string input with example `1000.00000000`; do not use `InputNumber`.
- disable submit until all five fields are non-empty.
- show normalized ProblemDetail in the modal on list/create failure using existing `toProblemDetail` style.

Do not create a raw/manual `billingPeriodId` text input.

- [ ] **Step 6: Run Budget tests and verify GREEN**

```powershell
npm test -- --run src/features/budgets/BudgetPages.test.tsx
```

Expected: Budget create permission, real BillingPeriod selection, exact decimal submission, and list invalidation tests pass; existing read-model/commitment tests remain green.

- [ ] **Step 7: Run the combined frontend focused gate**

```powershell
npm test -- --run `
  src/features/auth/AuthSessionProvider.test.tsx `
  src/features/auth/authSession.test.ts `
  src/features/auth/crossTabAuthCoordinator.test.ts `
  src/features/settings/users/UsersPage.test.tsx `
  src/features/budgets/BudgetPages.test.tsx
```

Expected: all focused tests pass.

- [ ] **Step 8: Commit Task 4**

```powershell
Set-Location ..
git add frontend/src/features/budgets/api/budgetApi.ts `
        frontend/src/features/budgets/api/billingPeriodApi.ts `
        frontend/src/features/budgets/BudgetsListPage.tsx `
        frontend/src/features/budgets/BudgetPages.test.tsx
git commit -m "feat(budget): create budgets from billing periods"
```

---

### Task 5: Full M4 final-closure verification and handoff

**Files:**
- No new product files unless verification exposes a scoped defect.
- Verify all changed files remain attributable to #80/#82/#84 plus design/plan docs.

**Interfaces:**
- Produces a pushed `fix/m4-final-closure` branch ready for Sol review and one final Draft PR.

- [ ] **Step 1: Run backend full verification**

```powershell
Set-Location backend
.\mvnw.cmd -B -DexcludedGroups=architecture,integration test
.\mvnw.cmd -B -Dgroups=integration verify
.\mvnw.cmd -B -Dgroups=architecture test
```

Expected: all three commands exit 0.

- [ ] **Step 2: Run frontend full verification**

```powershell
Set-Location ..\frontend
npm test -- --run
npm run lint
npm run build
```

Expected: all commands exit 0. Existing Vite chunk-size warning is acceptable if unchanged; new lint/build errors are not.

- [ ] **Step 3: Verify repository hygiene and scope**

```powershell
Set-Location ..
git diff --check
git status --short --branch
git diff --stat main...HEAD
git diff --name-only main...HEAD
```

Expected:

- `git diff --check` exits 0.
- `.zcode/` and `start-dev.bat` may appear only as untracked local files; they must not appear in `git diff --name-only main...HEAD`.
- No Redis Lua, auth backend, Flyway, Ledger/AIC-047+, dependency, or unrelated refactor files appear.

- [ ] **Step 4: Push branch**

```powershell
git push -u origin fix/m4-final-closure
```

Expected: remote branch updated successfully.

- [ ] **Step 5: Report exact evidence to Sol; do not create/merge PR unless instructed**

Send:

```text
branch: fix/m4-final-closure
base: main@27b873193b66b74b7b23f53904128e3fe0f9ce05
head: <git rev-parse HEAD>

commits:
<git log --oneline main..HEAD>

backend unit: PASS / exact count if available
backend integration: PASS / exact count if available
backend architecture: PASS / exact count if available
frontend full: PASS / exact files + tests
frontend lint: PASS
frontend build: PASS
git diff --check: PASS

changed files:
<git diff --name-only main...HEAD>

untracked local only:
.zcode/
start-dev.bat

#80 semantics: full role label visible; role ID payload unchanged
#82 semantics: BillingPeriod list org-scoped; Budget create uses selected backend period; decimal string unchanged
#84 semantics: initial anonymous AUTH_SESSION_EXPIRED silent; runtime terminal invalidation unchanged

PR: not created
merge: not performed
```

Stop after reporting. Sol performs independent review, opens the final Draft PR, checks GitHub CI, then the user performs the one final M4 UAT pass.
