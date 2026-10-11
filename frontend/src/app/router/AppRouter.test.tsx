import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from '../../features/auth/AuthSessionProvider'
import { AppRouter } from './AppRouter'

vi.mock('../../features/auth/AuthSessionProvider', () => ({ useAuth: vi.fn() }))
vi.mock('../../features/evidence/EvidenceListPage', () => ({
  EvidenceListPage: () => <h1>Evidence list page</h1>,
}))
vi.mock('../../features/evidence/EvidenceDetailPage', () => ({
  EvidenceDetailPage: () => <h1>Evidence detail page</h1>,
}))
vi.mock('../../features/imports/ImportListPage', () => ({
  ImportListPage: () => <h1>Import list page</h1>,
}))
vi.mock('../../features/imports/ImportDetailPage', () => ({
  ImportDetailPage: () => <h1>Import detail page</h1>,
}))
vi.mock('../../features/costs/CostsListPage', () => ({
  CostsListPage: () => <h1>Costs list page</h1>,
}))
vi.mock('../../features/costs/CostDetailPage', () => ({
  CostDetailPage: () => <h1>Cost detail page</h1>,
}))
vi.mock('../../features/duplicates/DuplicatesPage', () => ({
  DuplicatesPage: () => <h1>Duplicates page</h1>,
}))
vi.mock('../../features/allocation-rules/RulesPage', () => ({
  RulesPage: () => <h1>Rules page</h1>,
}))
vi.mock('../../features/expenses/ExpensesListPage', () => ({
  ExpensesListPage: () => <h1>Expenses list page</h1>,
}))
vi.mock('../../features/budgets/BudgetsListPage', () => ({
  BudgetsListPage: () => <h1>Budgets list page</h1>,
}))
vi.mock('../../features/budgets/BudgetDetailPage', () => ({
  BudgetDetailPage: () => <h1>Budget detail page</h1>,
}))
vi.mock('../../features/budgets/BudgetCommitmentDetailPage', () => ({
  BudgetCommitmentDetailPage: () => <h1>Commitment detail page</h1>,
}))
vi.mock('../../features/ledger/LedgerListPage', () => ({
  LedgerListPage: () => <h1>Ledger list page</h1>,
}))
vi.mock('../../features/ledger/LedgerPostingDetailPage', () => ({
  LedgerPostingDetailPage: () => <h1>Ledger posting page</h1>,
}))
vi.mock('../../features/ledger/LedgerEntryDetailPage', () => ({
  LedgerEntryDetailPage: () => <h1>Ledger entry page</h1>,
}))
vi.mock('../../features/settings/users/UsersPage', () => ({
  UsersPage: () => <h1>Users page</h1>,
}))
vi.mock('../../features/workbench/WorkbenchPage', () => ({
  WorkbenchPage: () => <h1>Workbench page</h1>,
}))

const mockedUseAuth = vi.mocked(useAuth)

function renderRouter(permissions: string[], initialPath: string) {
  window.history.pushState({}, '', initialPath)
  mockedUseAuth.mockReturnValue({
    status: 'authenticated',
    user: { id: '1', email: 'admin@example.com', displayName: 'Admin', organizationId: '2', organizationMemberId: '3', permissions },
    login: vi.fn(),
    refreshMe: vi.fn(),
    logout: vi.fn(),
  } as ReturnType<typeof useAuth>)
  return render(<AppRouter />)
}

beforeEach(() => {
  vi.clearAllMocks()
  window.history.pushState({}, '', '/')
})

describe('AppRouter permission gates', () => {
  it('directEvidenceRouteWithoutEvidenceReadRendersForbiddenAndNeverMountsChildPage', async () => {
    renderRouter([], '/evidence/5')

    expect(await screen.findByRole('heading', { name: '403' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Evidence detail page' })).not.toBeInTheDocument()
  })

  it('directImportRouteWithoutImportReadRendersForbiddenAndNeverMountsChildPage', async () => {
    renderRouter(['EVIDENCE_READ'], '/imports/123')

    expect(await screen.findByRole('heading', { name: '403' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Import detail page' })).not.toBeInTheDocument()
  })

  it('evidenceListRouteMountsPageWithEvidenceRead', async () => {
    renderRouter(['EVIDENCE_READ'], '/evidence')

    expect(await screen.findByRole('heading', { name: 'Evidence list page' })).toBeInTheDocument()
  })

  it('importListRouteMountsPageWithImportRead', async () => {
    renderRouter(['IMPORT_READ'], '/imports')

    expect(await screen.findByRole('heading', { name: 'Import list page' })).toBeInTheDocument()
  })

  it('evidenceDetailWithoutImportReadStillMountsDetailPage', async () => {
    renderRouter(['EVIDENCE_READ'], '/evidence/5')

    expect(await screen.findByRole('heading', { name: 'Evidence detail page' })).toBeInTheDocument()
  })

  it('importDetailWithoutEvidenceReadStillMountsDetailPage', async () => {
    renderRouter(['IMPORT_READ'], '/imports/123')

    expect(await screen.findByRole('heading', { name: 'Import detail page' })).toBeInTheDocument()
  })

  it('costsRouteWithoutCostReadRendersForbidden', async () => {
    renderRouter([], '/costs')

    expect(await screen.findByRole('heading', { name: '403' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Costs list page' })).not.toBeInTheDocument()
  })

  it('costsListMountsWithCostRead', async () => {
    renderRouter(['COST_READ'], '/costs')

    expect(await screen.findByRole('heading', { name: 'Costs list page' })).toBeInTheDocument()
  })

  it('costDetailMountsWithCostRead', async () => {
    renderRouter(['COST_READ'], '/costs/123')

    expect(await screen.findByRole('heading', { name: 'Cost detail page' })).toBeInTheDocument()
  })

  it('duplicatesRouteRequiresDuplicateReviewEvenWithCostRead', async () => {
    renderRouter(['COST_READ'], '/costs/duplicates')

    expect(await screen.findByRole('heading', { name: '403' })).toBeInTheDocument()
  })

  it('duplicatesRouteMountsWithDuplicateReview', async () => {
    renderRouter(['COST_READ', 'DUPLICATE_REVIEW'], '/costs/duplicates')

    expect(await screen.findByRole('heading', { name: 'Duplicates page' })).toBeInTheDocument()
  })

  it('allocationRulesRouteRequiresRuleManage', async () => {
    renderRouter([], '/allocation-rules')

    expect(await screen.findByRole('heading', { name: '403' })).toBeInTheDocument()
  })

  it('allocationRulesRouteMountsWithRuleManage', async () => {
    renderRouter(['ALLOCATION_RULE_MANAGE'], '/allocation-rules')

    expect(await screen.findByRole('heading', { name: 'Rules page' })).toBeInTheDocument()
  })
})

describe('AppRouter application landing', () => {
  it('appRootLandsOnEvidenceForEvidenceReadOnlyUser', async () => {
    renderRouter(['EVIDENCE_READ'], '/app')

    expect(await screen.findByRole('heading', { name: 'Evidence list page' })).toBeInTheDocument()
  })

  it('appRootLandsOnImportsForImportReadOnlyUser', async () => {
    renderRouter(['IMPORT_READ'], '/app')

    expect(await screen.findByRole('heading', { name: 'Import list page' })).toBeInTheDocument()
  })

  it('appRootLandsOnWorkbenchForCostReadOnlyUser', async () => {
    renderRouter(['COST_READ'], '/app')

    expect(await screen.findByRole('heading', { name: 'Workbench page' })).toBeInTheDocument()
  })

  it('appRootLandsOnRulesForRuleManageOnlyUser', async () => {
    renderRouter(['ALLOCATION_RULE_MANAGE'], '/app')

    expect(await screen.findByRole('heading', { name: 'Rules page' })).toBeInTheDocument()
  })

  it('appRootLandsOnFirstSettingsRouteForSettingsOnlyUser', async () => {
    renderRouter(['USER_READ'], '/app')

    expect(await screen.findByRole('heading', { name: 'Users page' })).toBeInTheDocument()
  })

  it('authenticatedWildcardReturnsToAppLanding', async () => {
    renderRouter(['EVIDENCE_READ'], '/unknown-route')

    expect(await screen.findByRole('heading', { name: 'Evidence list page' })).toBeInTheDocument()
  })
})
describe('AppRouter budget gates', () => {
  it('budgetsRouteWithoutBudgetReadRendersForbiddenAndNeverMountsChildPage', async () => {
    renderRouter([], '/budgets/5')

    expect(await screen.findByRole('heading', { name: '403' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Budget detail page' })).not.toBeInTheDocument()
  })

  it('budgetsRouteWithoutBudgetReadStillForbiddenWithManagePermissionsOnly', async () => {
    renderRouter(['BUDGET_MANAGE', 'COMMITMENT_APPROVE'], '/budgets')

    expect(await screen.findByRole('heading', { name: '403' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Budgets list page' })).not.toBeInTheDocument()
  })

  it('budgetsListMountsWithBudgetRead', async () => {
    renderRouter(['BUDGET_READ'], '/budgets')

    expect(await screen.findByRole('heading', { name: 'Budgets list page' })).toBeInTheDocument()
  })

  it('budgetDetailMountsWithBudgetRead', async () => {
    renderRouter(['BUDGET_READ'], '/budgets/5')

    expect(await screen.findByRole('heading', { name: 'Budget detail page' })).toBeInTheDocument()
  })

  it('commitmentDetailRouteWithoutBudgetReadRendersForbidden', async () => {
    renderRouter([], '/budget-commitments/9')

    expect(await screen.findByRole('heading', { name: '403' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Commitment detail page' })).not.toBeInTheDocument()
  })

  it('commitmentDetailMountsWithBudgetRead', async () => {
    renderRouter(['BUDGET_READ'], '/budget-commitments/9')

    expect(await screen.findByRole('heading', { name: 'Commitment detail page' })).toBeInTheDocument()
  })

  it('appRootLandsOnWorkbenchForBudgetReadOnlyUser', async () => {
    renderRouter(['BUDGET_READ'], '/app')

    expect(await screen.findByRole('heading', { name: 'Workbench page' })).toBeInTheDocument()
  })

  it('appRootLandsOnWorkbenchWhenBudgetsAndExpensesAreReadable', async () => {
    renderRouter(['BUDGET_READ', 'EXPENSE_READ_OWN'], '/app')

    expect(await screen.findByRole('heading', { name: 'Workbench page' })).toBeInTheDocument()
  })
})

describe('AppRouter ledger gates', () => {
  it('ledgerRouteWithoutLedgerReadRendersForbidden', async () => {
    renderRouter([], '/ledger')
    expect(await screen.findByRole('heading', { name: '403' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Ledger list page' })).not.toBeInTheDocument()
  })

  it('ledgerRoutesMountWithLedgerRead', async () => {
    renderRouter(['LEDGER_READ'], '/ledger/postings/900')
    expect(await screen.findByRole('heading', { name: 'Ledger posting page' })).toBeInTheDocument()
  })
})
