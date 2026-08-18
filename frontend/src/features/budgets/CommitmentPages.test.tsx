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

function renderAs(permissions: string[], memberId = '3') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  mockedUseAuth.mockReturnValue({
    status: 'authenticated',
    user: {
      id: '1',
      email: 'admin@example.com',
      displayName: 'Admin',
      organizationId: '2',
      organizationMemberId: memberId,
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
    renderAs(['BUDGET_READ'])

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
    renderAs(['BUDGET_READ'])

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

    renderAs(['BUDGET_READ', 'COMMITMENT_APPROVE', 'COMMITMENT_RELEASE'])

    await waitFor(() => expect(screen.getByText('承诺详情 #9')).toBeInTheDocument())
    expect(screen.getByText('已消耗')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /批\s*准/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /拒\s*绝/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /取消/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /释\s*放/ })).not.toBeInTheDocument()
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

    renderAs(['BUDGET_READ'], '99')

    await waitFor(() => expect(screen.getAllByText('待审批').length).toBeGreaterThanOrEqual(1))
    expect(screen.queryByRole('button', { name: /批\s*准/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /拒\s*绝/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /取消/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /释\s*放/ })).not.toBeInTheDocument()
  })

  it('navigates back to the owning budget', async () => {
    renderAs(['BUDGET_READ'])

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

    renderAs(['BUDGET_READ'])

    await waitFor(() => {
      expect(screen.getByText(/Commitment not found（RESOURCE_NOT_FOUND）/)).toBeInTheDocument()
    })
  })
})
const REQUESTED_COMMITMENT: CommitmentResponse = {
  ...COMMITMENT,
  status: 'REQUESTED',
  approvedAmount: null,
  remainingAmount: null,
  approvalStatus: 'PENDING',
  version: 1,
  updatedAt: '2026-01-03T00:00:00Z',
}

describe('Commitment lifecycle actions', () => {
  describe('approve', () => {
    it('offers approve only on REQUESTED with COMMITMENT_APPROVE', async () => {
      mockedCommitmentApi.get.mockResolvedValue(REQUESTED_COMMITMENT)
      renderAs(['BUDGET_READ'])
      await waitFor(() => expect(screen.getByText('承诺详情 #9')).toBeInTheDocument())
      expect(screen.queryByRole('button', { name: /批\s*准/ })).not.toBeInTheDocument()

      mockedCommitmentApi.get.mockResolvedValue(REQUESTED_COMMITMENT)
      renderAs(['BUDGET_READ', 'COMMITMENT_APPROVE'])
      await waitFor(() => expect(screen.getByRole('button', { name: /批\s*准/ })).toBeInTheDocument())
    })

    it('sends expectedVersion and an Idempotency-Key', async () => {
      mockedCommitmentApi.get.mockResolvedValue(REQUESTED_COMMITMENT)
      mockedCommitmentApi.approve.mockResolvedValue(COMMITMENT)
      renderAs(['BUDGET_READ', 'COMMITMENT_APPROVE'])
      await waitFor(() => expect(screen.getByRole('button', { name: /批\s*准/ })).toBeInTheDocument())

      fireEvent.click(screen.getByRole('button', { name: /批\s*准/ }))

      await waitFor(() => {
        expect(mockedCommitmentApi.approve).toHaveBeenCalledWith('9', { expectedVersion: 1 }, expect.any(String))
      })
    })

    it('refreshes the budget financial read model after approval', async () => {
      mockedCommitmentApi.get.mockResolvedValue(REQUESTED_COMMITMENT)
      mockedCommitmentApi.approve.mockResolvedValue(COMMITMENT)
      renderAs(['BUDGET_READ', 'COMMITMENT_APPROVE'])
      await waitFor(() => expect(screen.getByRole('button', { name: /批\s*准/ })).toBeInTheDocument())

      fireEvent.click(screen.getByRole('button', { name: /批\s*准/ }))

      // Commitment detail and the owning budget are both refetched: approval
      // changes committedAmount/availableAmount/overBudget on the budget.
      await waitFor(() => {
        expect(mockedCommitmentApi.get.mock.calls.length).toBeGreaterThanOrEqual(2)
      })
      await waitFor(() => {
        expect(mockedBudgetApi.get.mock.calls.length).toBeGreaterThanOrEqual(2)
      })
    })

    it('shows BUDGET_INSUFFICIENT without auto-retrying', async () => {
      mockedCommitmentApi.get.mockResolvedValue(REQUESTED_COMMITMENT)
      mockedCommitmentApi.approve.mockRejectedValue({
        isAxiosError: true,
        response: {
          data: {
            title: 'Insufficient budget',
            status: 409,
            detail: 'Not enough availability.',
            code: 'BUDGET_INSUFFICIENT',
            traceId: 't-4',
          },
        },
      })
      renderAs(['BUDGET_READ', 'COMMITMENT_APPROVE'])
      await waitFor(() => expect(screen.getByRole('button', { name: /批\s*准/ })).toBeInTheDocument())

      fireEvent.click(screen.getByRole('button', { name: /批\s*准/ }))

      await waitFor(() => {
        expect(screen.getByText('Budget availability is insufficient.')).toBeInTheDocument()
      })
      expect(mockedCommitmentApi.approve).toHaveBeenCalledTimes(1)
    })

    it('shows PERIOD_NOT_OPEN without auto-retrying', async () => {
      mockedCommitmentApi.get.mockResolvedValue(REQUESTED_COMMITMENT)
      mockedCommitmentApi.approve.mockRejectedValue({
        isAxiosError: true,
        response: {
          data: {
            title: 'Period closed',
            status: 409,
            detail: 'The financial period is closed.',
            code: 'PERIOD_NOT_OPEN',
            traceId: 't-5',
          },
        },
      })
      renderAs(['BUDGET_READ', 'COMMITMENT_APPROVE'])
      await waitFor(() => expect(screen.getByRole('button', { name: /批\s*准/ })).toBeInTheDocument())

      fireEvent.click(screen.getByRole('button', { name: /批\s*准/ }))

      await waitFor(() => {
        expect(screen.getByText('Current financial period does not allow this action.')).toBeInTheDocument()
      })
      expect(mockedCommitmentApi.approve).toHaveBeenCalledTimes(1)
    })
  })

  describe('reject', () => {
    it('offers reject with a comment on REQUESTED for approvers', async () => {
      mockedCommitmentApi.get.mockResolvedValue(REQUESTED_COMMITMENT)
      mockedCommitmentApi.reject.mockResolvedValue({
        ...REQUESTED_COMMITMENT,
        status: 'REJECTED',
        approvalStatus: 'REJECTED',
        version: 2,
      })
      renderAs(['BUDGET_READ', 'COMMITMENT_APPROVE'])
      await waitFor(() => expect(screen.getByRole('button', { name: /拒\s*绝/ })).toBeInTheDocument())

      fireEvent.click(screen.getByRole('button', { name: /拒\s*绝/ }))
      fireEvent.change(screen.getByLabelText('拒绝原因'), { target: { value: 'duplicate request' } })
      fireEvent.click(screen.getByRole('button', { name: /确认拒绝/ }))

      await waitFor(() => {
        expect(mockedCommitmentApi.reject).toHaveBeenCalledWith('9', {
          expectedVersion: 1,
          comment: 'duplicate request',
        }, expect.any(String))
      })
    })

    it('refreshes the history after a rejection', async () => {
      mockedCommitmentApi.get.mockResolvedValue(REQUESTED_COMMITMENT)
      mockedCommitmentApi.reject.mockResolvedValue({
        ...REQUESTED_COMMITMENT,
        status: 'REJECTED',
        approvalStatus: 'REJECTED',
        version: 2,
      })
      renderAs(['BUDGET_READ', 'COMMITMENT_APPROVE'])
      await waitFor(() => expect(screen.getByRole('button', { name: /拒\s*绝/ })).toBeInTheDocument())
      fireEvent.click(screen.getByRole('button', { name: /拒\s*绝/ }))
      fireEvent.change(screen.getByLabelText('拒绝原因'), { target: { value: 'no' } })
      fireEvent.click(screen.getByRole('button', { name: /确认拒绝/ }))

      await waitFor(() => {
        expect(mockedCommitmentApi.get.mock.calls.length).toBeGreaterThanOrEqual(2)
      })
    })
  })

  describe('cancel', () => {
    it('lets the original SUBMIT actor cancel a REQUESTED commitment', async () => {
      mockedCommitmentApi.get.mockResolvedValue(REQUESTED_COMMITMENT)
      mockedCommitmentApi.cancel.mockResolvedValue({
        ...REQUESTED_COMMITMENT,
        status: 'CANCELED',
        approvalStatus: 'CANCELED',
        version: 2,
      })
      renderAs(['BUDGET_READ'])
      await waitFor(() => expect(screen.getByRole('button', { name: /取消申请/ })).toBeInTheDocument())
    })

    it('hides cancel from an unrelated member without COMMITMENT_APPROVE', async () => {
      mockedCommitmentApi.get.mockResolvedValue(REQUESTED_COMMITMENT)
      renderAs(['BUDGET_READ', 'COMMITMENT_REQUEST'], '99')
      await waitFor(() => expect(screen.getByText('承诺详情 #9')).toBeInTheDocument())
      expect(screen.queryByRole('button', { name: /取消申请/ })).not.toBeInTheDocument()
    })

    it('shows cancel to an approver even when not the requester', async () => {
      mockedCommitmentApi.get.mockResolvedValue(REQUESTED_COMMITMENT)
      mockedCommitmentApi.cancel.mockResolvedValue({
        ...REQUESTED_COMMITMENT,
        status: 'CANCELED',
        approvalStatus: 'CANCELED',
        version: 2,
      })
      renderAs(['BUDGET_READ', 'COMMITMENT_APPROVE'], '99')
      await waitFor(() => expect(screen.getByRole('button', { name: /取消申请/ })).toBeInTheDocument())
    })

    it('never offers cancel on ACTIVE', async () => {
      renderAs(['BUDGET_READ', 'COMMITMENT_APPROVE'])
      await waitFor(() => expect(screen.getByText('承诺详情 #9')).toBeInTheDocument())
      expect(screen.queryByRole('button', { name: /取消申请/ })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /批\s*准/ })).not.toBeInTheDocument()
    })

    it('cancels with expectedVersion and an Idempotency-Key', async () => {
      mockedCommitmentApi.get.mockResolvedValue(REQUESTED_COMMITMENT)
      mockedCommitmentApi.cancel.mockResolvedValue({
        ...REQUESTED_COMMITMENT,
        status: 'CANCELED',
        approvalStatus: 'CANCELED',
        version: 2,
      })
      renderAs(['BUDGET_READ'], '3')
      await waitFor(() => expect(screen.getByRole('button', { name: /取消申请/ })).toBeInTheDocument())
      fireEvent.click(screen.getByRole('button', { name: /取消申请/ }))
      fireEvent.click(screen.getByRole('button', { name: /确认取消/ }))

      await waitFor(() => {
        expect(mockedCommitmentApi.cancel).toHaveBeenCalledWith('9', { expectedVersion: 1 }, expect.any(String))
      })
    })
  })

  describe('release', () => {
    it('offers release only with COMMITMENT_RELEASE on ACTIVE', async () => {
      renderAs(['BUDGET_READ'])
      await waitFor(() => expect(screen.getByText('承诺详情 #9')).toBeInTheDocument())
      expect(screen.queryByRole('button', { name: /释\s*放/ })).not.toBeInTheDocument()

      renderAs(['BUDGET_READ', 'COMMITMENT_RELEASE'])
      await waitFor(() => expect(screen.getByRole('button', { name: /释\s*放/ })).toBeInTheDocument())
    })

    it('allows release on PARTIALLY_CONSUMED', async () => {
      mockedCommitmentApi.get.mockResolvedValue({
        ...COMMITMENT,
        status: 'PARTIALLY_CONSUMED',
        version: 4,
      })
      renderAs(['BUDGET_READ', 'COMMITMENT_RELEASE'])
      await waitFor(() => expect(screen.getByRole('button', { name: /释\s*放/ })).toBeInTheDocument())
    })

    it('hides release on CONSUMED', async () => {
      mockedCommitmentApi.get.mockResolvedValue({
        ...COMMITMENT,
        status: 'CONSUMED',
      })
      renderAs(['BUDGET_READ', 'COMMITMENT_RELEASE'])
      await waitFor(() => expect(screen.getByText('已消耗')).toBeInTheDocument())
      expect(screen.queryByRole('button', { name: /释\s*放/ })).not.toBeInTheDocument()
    })

    it('confirms release with remaining amount, currency and commitment id', async () => {
      renderAs(['BUDGET_READ', 'COMMITMENT_RELEASE'])
      await waitFor(() => expect(screen.getByRole('button', { name: /释\s*放/ })).toBeInTheDocument())

      fireEvent.click(screen.getByRole('button', { name: /释\s*放/ }))

      expect(screen.getByText('Commitment ID')).toBeInTheDocument()
      expect(screen.getByText('Remaining Amount')).toBeInTheDocument()
      expect(screen.getAllByText('CNY').length).toBeGreaterThanOrEqual(1)
      expect(screen.getAllByText('40.00000000 CNY').length).toBeGreaterThanOrEqual(2)
      // Nothing is released before the confirm button.
      expect(mockedCommitmentApi.release).not.toHaveBeenCalled()
    })

    it('releases with expectedVersion, an Idempotency-Key and refreshes budget metrics', async () => {
      mockedCommitmentApi.release.mockResolvedValue({
        ...COMMITMENT,
        status: 'RELEASED',
        remainingAmount: null,
        version: 4,
      })
      renderAs(['BUDGET_READ', 'COMMITMENT_RELEASE'])
      await waitFor(() => expect(screen.getByRole('button', { name: /释\s*放/ })).toBeInTheDocument())
      fireEvent.click(screen.getByRole('button', { name: /释\s*放/ }))
      fireEvent.click(screen.getByRole('button', { name: /确认释放/ }))

      await waitFor(() => {
        expect(mockedCommitmentApi.release).toHaveBeenCalledWith('9', { expectedVersion: 3 }, expect.any(String))
      })
      // Release frees the committed remainder: the budget read model refreshes.
      await waitFor(() => {
        expect(mockedBudgetApi.get.mock.calls.length).toBeGreaterThanOrEqual(2)
      })
    })
  })
})
