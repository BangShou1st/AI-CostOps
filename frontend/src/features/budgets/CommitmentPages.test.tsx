import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { useAuth } from '../auth/AuthSessionProvider'
import { budgetApi, type BudgetResponse } from './api/budgetApi'
import { commitmentApi, type ApprovalActionResponse, type CommitmentResponse } from './api/commitmentApi'
import { BudgetCommitmentDetailPage } from './BudgetCommitmentDetailPage'

vi.mock('../auth/AuthSessionProvider', () => ({ useAuth: vi.fn() }))
vi.mock('./api/budgetApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./api/budgetApi')>()
  return {
    ...actual,
    budgetApi: { list: vi.fn(), get: vi.fn(), update: vi.fn() },
  }
})
vi.mock('./api/commitmentApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./api/commitmentApi')>()
  return {
    ...actual,
    commitmentApi: {
      list: vi.fn(),
      get: vi.fn(),
      create: vi.fn(),
      approve: vi.fn(),
      reject: vi.fn(),
      cancel: vi.fn(),
      release: vi.fn(),
    },
  }
})

const mockedUseAuth = vi.mocked(useAuth)
const mockedBudgetApi = vi.mocked(budgetApi)
const mockedCommitmentApi = vi.mocked(commitmentApi)

const BUDGET: BudgetResponse = {
  id: '7',
  billingPeriodId: '3',
  scopeType: 'PROJECT',
  scopeId: '42',
  currency: 'CNY',
  totalAmount: '100.00000000',
  actualAmount: '30.00000000',
  committedAmount: '20.00000000',
  availableAmount: '48.50000000',
  overBudget: false,
  status: 'ACTIVE',
  version: 4,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-02T00:00:00Z',
}

const HISTORY: ApprovalActionResponse[] = [
  {
    id: '1',
    approvalCaseId: 'c-1',
    actorMemberId: '3',
    actionType: 'SUBMIT',
    fromState: null,
    toState: 'REQUESTED',
    comment: null,
    createdAt: '2026-01-03T00:00:00Z',
  },
  {
    id: '2',
    approvalCaseId: 'c-1',
    actorMemberId: '5',
    actionType: 'APPROVE',
    fromState: 'REQUESTED',
    toState: 'ACTIVE',
    comment: 'ok',
    createdAt: '2026-01-04T00:00:00Z',
  },
]

const COMMITMENT: CommitmentResponse = {
  id: '9',
  budgetId: '7',
  status: 'ACTIVE',
  requestedAmount: '50.00000000',
  approvedAmount: '50.00000000',
  remainingAmount: '40.00000000',
  version: 3,
  createdAt: '2026-01-03T00:00:00Z',
  updatedAt: '2026-01-04T00:00:00Z',
  approvalCaseId: 'c-1',
  approvalStatus: 'APPROVED',
  history: HISTORY,
}

function renderPage(permissions: string[]) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  mockedUseAuth.mockReturnValue({
    status: 'authenticated',
    user: {
      id: '1',
      email: 'admin@example.com',
      displayName: 'Admin',
      organizationId: '2',
      organizationMemberId: '3',
      permissions,
    },
    login: vi.fn(),
    refreshMe: vi.fn(),
    logout: vi.fn(),
  } as ReturnType<typeof useAuth>)
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/budget-commitments/9']}>
        <Routes>
          <Route path="/budget-commitments/:commitmentId" element={<BudgetCommitmentDetailPage />} />
          <Route path="/budgets/:budgetId" element={<h1>Budget page</h1>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  mockedBudgetApi.get.mockResolvedValue(BUDGET)
  mockedCommitmentApi.get.mockResolvedValue(COMMITMENT)
})

describe('BudgetCommitmentDetailPage', () => {
  it('renders the commitment detail fields', async () => {
    renderPage(['BUDGET_READ'])

    await waitFor(() => expect(screen.getByText('承诺详情 #9')).toBeInTheDocument())
    // The currency comes from the owning budget query; wait for it to resolve.
    await waitFor(() => expect(screen.getAllByText('50.00000000 CNY').length).toBeGreaterThanOrEqual(2))
    expect(screen.getByText('已生效')).toBeInTheDocument()
    expect(screen.getByText('40.00000000 CNY')).toBeInTheDocument()
    expect(screen.getByText('7')).toBeInTheDocument()
    expect(screen.getByText('已批准')).toBeInTheDocument()
    expect(screen.getByText('v3')).toBeInTheDocument()
  })

  it('renders the full append-only history with action, from, to, actor and comment', async () => {
    renderPage(['BUDGET_READ'])

    await waitFor(() => expect(screen.getByText('提交')).toBeInTheDocument())
    expect(screen.getByText('批准')).toBeInTheDocument()
    expect(screen.getByText('2026-01-03T00:00:00Z')).toBeInTheDocument()
    expect(screen.getByText('2026-01-04T00:00:00Z')).toBeInTheDocument()
    expect(screen.getAllByText('REQUESTED').length).toBeGreaterThanOrEqual(1)
    expect(screen.getByText('ACTIVE')).toBeInTheDocument()
    expect(screen.getAllByText('3').length).toBeGreaterThanOrEqual(1)
    expect(screen.getByText('5')).toBeInTheDocument()
    expect(screen.getByText('ok')).toBeInTheDocument()
  })

  it('keeps a terminal state read-only without any action', async () => {
    mockedCommitmentApi.get.mockResolvedValue({
      ...COMMITMENT,
      status: 'CONSUMED',
      approvalStatus: 'APPROVED',
    })

    renderPage(['BUDGET_READ', 'COMMITMENT_APPROVE', 'COMMITMENT_RELEASE'])

    await waitFor(() => expect(screen.getByText('承诺详情 #9')).toBeInTheDocument())
    expect(screen.getByText('已消耗')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /批准/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /拒绝/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /取消/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /释放/ })).not.toBeInTheDocument()
  })

  it('hides lifecycle actions from a plain reader even on REQUESTED', async () => {
    mockedCommitmentApi.get.mockResolvedValue({
      ...COMMITMENT,
      status: 'REQUESTED',
      approvedAmount: null,
      remainingAmount: null,
      approvalStatus: 'PENDING',
      version: 1,
    })

    renderPage(['BUDGET_READ'])

    await waitFor(() => expect(screen.getAllByText('待审批').length).toBeGreaterThanOrEqual(1))
    expect(screen.queryByRole('button', { name: /批准/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /拒绝/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /取消/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /释放/ })).not.toBeInTheDocument()
  })

  it('navigates back to the owning budget', async () => {
    renderPage(['BUDGET_READ'])

    await waitFor(() => expect(screen.getByText('承诺详情 #9')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: /返回预算/ }))

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Budget page' })).toBeInTheDocument())
  })

  it('shows the normalized problem detail on a 404', async () => {
    mockedCommitmentApi.get.mockRejectedValue({
      isAxiosError: true,
      response: {
        data: {
          title: 'Commitment not found',
          status: 404,
          detail: 'The commitment is not available in the current organization.',
          code: 'RESOURCE_NOT_FOUND',
          traceId: 't-7',
        },
      },
    })

    renderPage(['BUDGET_READ'])

    await waitFor(() => {
      expect(screen.getByText(/Commitment not found（RESOURCE_NOT_FOUND）/)).toBeInTheDocument()
    })
  })
})
