import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from '../auth/AuthSessionProvider'
import { WorkbenchPage } from './WorkbenchPage'
import { workbenchApi } from './api/workbenchApi'

vi.mock('../auth/AuthSessionProvider', () => ({ useAuth: vi.fn() }))
vi.mock('./api/workbenchApi', () => ({ workbenchApi: { get: vi.fn() } }))
vi.mock('../../api/problem', async (importOriginal) => await importOriginal())

const mockedUseAuth = vi.mocked(useAuth)
const mockedWorkbenchApi = vi.mocked(workbenchApi)

const FULL_VIEW = {
  period: { billingPeriodId: '55', periodStart: '2026-01-01T00:00:00Z', periodEnd: '2026-02-01T00:00:00Z', status: 'OPEN' as const },
  costByProvider: [{ providerCode: 'GLM', currency: 'CNY', totalAmount: '30.00000000', chargeCount: 2 }],
  costByProject: [],
  budgetVariance: [{
    budgetId: '81', scopeType: 'ORG' as const, scopeId: '2', currency: 'CNY',
    totalAmount: '100.00000000', actualAmount: '30.00000000', committedAmount: '10.00000000',
    availableAmount: '60.00000000', overBudget: false,
  }],
  unallocatedCharges: [{ currency: 'CNY', amount: '30.00000000', chargeCount: 2 }],
  duplicateCandidates: { openCount: 3 },
  pendingApprovals: { submittedCount: 1, needsInfoCount: 2 },
  openReconciliations: { activeRunCount: 1, openCaseCount: 4 },
  closeStatus: { status: 'OPEN' as const, closing: false, closed: false },
}

function renderPage(permissions: string[]) {
  mockedUseAuth.mockReturnValue({
    status: 'authenticated',
    user: { id: '1', email: 'a@example.com', displayName: 'A', organizationId: '2', organizationMemberId: '3', permissions },
    login: vi.fn(), refreshMe: vi.fn(), logout: vi.fn(),
  } as ReturnType<typeof useAuth>)
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/workbench']}>
        <WorkbenchPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => vi.clearAllMocks())

describe('WorkbenchPage', () => {
  it('renders card-first sections with per-currency amounts and deep links', async () => {
    mockedWorkbenchApi.get.mockResolvedValue(FULL_VIEW)
    renderPage(['PERIOD_READ', 'COST_READ', 'BUDGET_READ', 'ALLOCATION_READ', 'DUPLICATE_REVIEW', 'EXPENSE_REVIEW', 'RECONCILIATION_READ'])

    await waitFor(() => expect(screen.getByText('未分摊成本')).toBeInTheDocument())
    expect(screen.getByText('30.00000000 CNY')).toBeInTheDocument()
    expect(screen.getByText('重复候选')).toBeInTheDocument()
    expect(screen.getByText('待审批报销')).toBeInTheDocument()
    expect(screen.getAllByText('进行中对账').length).toBeGreaterThan(0)
    expect(screen.getByText('预算偏差')).toBeInTheDocument()
    expect(screen.getByText('供应商成本')).toBeInTheDocument()
    expect(screen.getByText(/GLM:/)).toHaveTextContent('GLM: 30.00000000 CNY')
    expect(screen.getByRole('link', { name: '去审查' })).toHaveAttribute('href', '/costs/duplicates')
  })

  it('renders only the cards matching the caller grants', async () => {
    mockedWorkbenchApi.get.mockResolvedValue({
      duplicateCandidates: { openCount: 0 },
    })
    renderPage(['DUPLICATE_REVIEW'])

    await waitFor(() => expect(screen.getByText('重复候选')).toBeInTheDocument())
    expect(screen.queryByText('未分摊成本')).not.toBeInTheDocument()
    expect(screen.queryByText('预算偏差')).not.toBeInTheDocument()
    expect(screen.queryByText('工作台数据权限')).not.toBeInTheDocument()
  })

  it('shows the empty state when no section is granted', async () => {
    mockedWorkbenchApi.get.mockResolvedValue({})
    renderPage(['EVIDENCE_UPLOAD_OWN'])

    await waitFor(() => expect(screen.getByText('当前账号没有可展示的工作台数据权限')).toBeInTheDocument())
  })

  it('surfaces problem details when the aggregation fails', async () => {
    mockedWorkbenchApi.get.mockRejectedValue(new Error('boom'))
    renderPage(['COST_READ'])

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument())
  })
})
