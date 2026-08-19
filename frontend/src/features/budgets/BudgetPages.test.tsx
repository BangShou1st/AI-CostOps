import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { useAuth } from '../auth/AuthSessionProvider'
import { budgetApi, type BudgetResponse } from './api/budgetApi'
import { commitmentApi, type CommitmentResponse } from './api/commitmentApi'
import { BudgetsListPage } from './BudgetsListPage'
import { BudgetDetailPage } from './BudgetDetailPage'

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

/**
 * Contract sentinel: the server reports total - actual - committed as
 * 48.50000000 even though naive client arithmetic would compute 50. The
 * page must display the server authority verbatim and never recompute.
 */
const SENTINEL_BUDGET: BudgetResponse = {
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

const COMMITMENT: CommitmentResponse = {
  id: '9',
  budgetId: '7',
  status: 'REQUESTED',
  requestedAmount: '50.00000000',
  approvedAmount: null,
  remainingAmount: null,
  version: 1,
  createdAt: '2026-01-03T00:00:00Z',
  updatedAt: '2026-01-03T00:00:00Z',
  approvalCaseId: 'c-1',
  approvalStatus: 'PENDING',
  history: [],
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
      <MemoryRouter initialEntries={['/budgets']}>
        <Routes>
          <Route path="/budgets" element={<BudgetsListPage />} />
          <Route path="/budgets/:budgetId" element={<BudgetDetailPage />} />
          <Route path="/budget-commitments/:commitmentId" element={<h1>Commitment page</h1>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function renderDetailPage(permissions: string[]) {
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
      <MemoryRouter initialEntries={['/budgets/7']}>
        <Routes>
          <Route path="/budgets/:budgetId" element={<BudgetDetailPage />} />
          <Route path="/budget-commitments/:commitmentId" element={<h1>Commitment page</h1>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  mockedBudgetApi.list.mockResolvedValue({ items: [], page: 0, size: 50, totalElements: 0, totalPages: 0 })
  mockedBudgetApi.get.mockResolvedValue(SENTINEL_BUDGET)
  mockedCommitmentApi.list.mockResolvedValue({ items: [], page: 0, size: 10, totalElements: 0, totalPages: 0 })
})

describe('BudgetsListPage', () => {
  it('renders the five frozen budget metrics', async () => {
    mockedBudgetApi.list.mockResolvedValue({
      items: [SENTINEL_BUDGET], page: 0, size: 50, totalElements: 1, totalPages: 1,
    })

    renderPage(['BUDGET_READ'])

    await waitFor(() => expect(screen.getByText('100.00000000 CNY')).toBeInTheDocument())
    expect(screen.getByText('30.00000000 CNY')).toBeInTheDocument()
    expect(screen.getByText('20.00000000 CNY')).toBeInTheDocument()
    // scroll={{ x }} renders the header twice in jsdom; presence is the contract.
    expect(screen.getAllByText('总额').length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText('实际发生').length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText('未结承诺').length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText('可用额度').length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText('超支状态').length).toBeGreaterThanOrEqual(1)
  })

  it('displays the server availableAmount sentinel without recomputing it', async () => {
    // total 100 - actual 30 - committed 20 = 50 by naive arithmetic, but the
    // server authority says 48.50000000. The page must show the server value.
    mockedBudgetApi.list.mockResolvedValue({
      items: [SENTINEL_BUDGET], page: 0, size: 50, totalElements: 1, totalPages: 1,
    })

    renderPage(['BUDGET_READ'])

    await waitFor(() => expect(screen.getByText('48.50000000 CNY')).toBeInTheDocument())
    expect(screen.queryByText('50.00000000 CNY')).not.toBeInTheDocument()
  })

  it('renders the typed scope and scope id', async () => {
    mockedBudgetApi.list.mockResolvedValue({
      items: [SENTINEL_BUDGET], page: 0, size: 50, totalElements: 1, totalPages: 1,
    })

    renderPage(['BUDGET_READ'])

    await waitFor(() => expect(screen.getByText('项目 · 42')).toBeInTheDocument())
    expect(screen.getByText('3')).toBeInTheDocument()
    expect(screen.getByText('CNY')).toBeInTheDocument()
  })

  it('shows the server overBudget flag as a visible tag', async () => {
    mockedBudgetApi.list.mockResolvedValue({
      items: [{ ...SENTINEL_BUDGET, availableAmount: '-1.00000000', overBudget: true }],
      page: 0, size: 50, totalElements: 1, totalPages: 1,
    })

    renderPage(['BUDGET_READ'])

    await waitFor(() => expect(screen.getByText('超支')).toBeInTheDocument())
  })

  it('paginates through the server', async () => {
    mockedBudgetApi.list.mockResolvedValue({
      items: [SENTINEL_BUDGET], page: 0, size: 50, totalElements: 55, totalPages: 2,
    })

    renderPage(['BUDGET_READ'])

    await screen.findByText('100.00000000 CNY')
    fireEvent.click(screen.getByTitle('2'))

    await waitFor(() => {
      expect(mockedBudgetApi.list).toHaveBeenLastCalledWith({ page: 1, size: 50 })
    })
  })

  it('navigates to the budget detail on row click', async () => {
    mockedBudgetApi.list.mockResolvedValue({
      items: [SENTINEL_BUDGET], page: 0, size: 50, totalElements: 1, totalPages: 1,
    })

    renderPage(['BUDGET_READ'])

    await screen.findByText('100.00000000 CNY')
    fireEvent.click(screen.getByText('7'))

    await waitFor(() => expect(screen.getByText(/预算详情/)).toBeInTheDocument())
  })

  it('shows the normalized problem detail when the budget list fails', async () => {
    mockedBudgetApi.list.mockRejectedValue({
      isAxiosError: true,
      response: {
        data: {
          title: 'Forbidden',
          status: 403,
          detail: 'Access to this resource is forbidden.',
          code: 'FORBIDDEN',
          traceId: 't-9',
        },
      },
    })

    renderPage(['BUDGET_READ'])

    await waitFor(() => {
      expect(screen.getByText(/Forbidden（FORBIDDEN）/)).toBeInTheDocument()
    })
  })
})

describe('BudgetDetailPage', () => {
  it('renders the five frozen metrics and the identity fields', async () => {
    renderDetailPage(['BUDGET_READ'])

    await waitFor(() => expect(screen.getByText(/预算详情/)).toBeInTheDocument())
    expect(screen.getByText('100.00000000 CNY')).toBeInTheDocument()
    expect(screen.getByText('30.00000000 CNY')).toBeInTheDocument()
    expect(screen.getByText('20.00000000 CNY')).toBeInTheDocument()
    expect(screen.getByText('48.50000000 CNY')).toBeInTheDocument()
    expect(screen.getByText('总额')).toBeInTheDocument()
    expect(screen.getByText('实际发生')).toBeInTheDocument()
    expect(screen.getByText('未结承诺')).toBeInTheDocument()
    expect(screen.getByText('可用额度')).toBeInTheDocument()
    expect(screen.getByText('超支状态')).toBeInTheDocument()
    expect(screen.getByText('项目')).toBeInTheDocument()
    expect(screen.getByText('42')).toBeInTheDocument()
    expect(screen.getByText('3')).toBeInTheDocument()
    expect(screen.getByText('生效')).toBeInTheDocument()
    expect(screen.getAllByText('状态').length).toBeGreaterThanOrEqual(1)
    expect(screen.getByText('范围 ID')).toBeInTheDocument()
    expect(screen.getByText('账期 ID')).toBeInTheDocument()
    expect(screen.getByText('版本')).toBeInTheDocument()
    expect(screen.getByText('v4')).toBeInTheDocument()
  })

  it('does not recompute the available sentinel on the detail page either', async () => {
    renderDetailPage(['BUDGET_READ'])

    await waitFor(() => expect(screen.getByText('48.50000000 CNY')).toBeInTheDocument())
    expect(screen.queryByText('50.00000000 CNY')).not.toBeInTheDocument()
  })

  it('flags over-budget from the server field with a visible alert', async () => {
    mockedBudgetApi.get.mockResolvedValue({
      ...SENTINEL_BUDGET,
      availableAmount: '-2.00000000',
      overBudget: true,
    })

    renderDetailPage(['BUDGET_READ'])

    await waitFor(() => expect(screen.getByText('预算超支')).toBeInTheDocument())
    expect(screen.queryByText('预算超支')).toBeInTheDocument()
  })

  it('renders the commitment list of the budget', async () => {
    mockedCommitmentApi.list.mockResolvedValue({
      items: [COMMITMENT], page: 0, size: 10, totalElements: 1, totalPages: 1,
    })

    renderDetailPage(['BUDGET_READ'])

    await waitFor(() => expect(screen.getAllByText('待审批').length).toBeGreaterThanOrEqual(1))
    expect(screen.getByText('9')).toBeInTheDocument()
    expect(screen.getByText('50.00000000 CNY')).toBeInTheDocument()
    expect(screen.getByText('2026-01-03T00:00:00Z')).toBeInTheDocument()
    expect(screen.getAllByText('—').length).toBeGreaterThanOrEqual(2)
    // Commitment table headers are localized ('状态' also labels the identity card).
    expect(screen.getAllByText('状态').length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText('申请金额').length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText('批准金额').length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText('剩余金额').length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText('审批状态').length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText('创建时间').length).toBeGreaterThanOrEqual(1)
  })

  it('navigates to the commitment detail on row click', async () => {
    mockedCommitmentApi.list.mockResolvedValue({
      items: [COMMITMENT], page: 0, size: 10, totalElements: 1, totalPages: 1,
    })

    renderDetailPage(['BUDGET_READ'])

    await waitFor(() => expect(screen.getAllByText('待审批').length).toBeGreaterThanOrEqual(1))
    fireEvent.click(screen.getByText('9'))

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Commitment page' })).toBeInTheDocument())
  })

  it('shows the normalized problem detail on a 404 budget', async () => {
    mockedBudgetApi.get.mockRejectedValue({
      isAxiosError: true,
      response: {
        data: {
          title: 'Budget not found',
          status: 404,
          detail: 'The budget is not available in the current organization.',
          code: 'RESOURCE_NOT_FOUND',
          traceId: 't-5',
        },
      },
    })

    renderDetailPage(['BUDGET_READ'])

    await waitFor(() => {
      expect(screen.getByText(/Budget not found（RESOURCE_NOT_FOUND）/)).toBeInTheDocument()
    })
  })

  it('shows an error instead of an empty commitment list when the list fails', async () => {
    mockedCommitmentApi.list.mockRejectedValue({
      isAxiosError: true,
      response: {
        data: {
          title: 'Forbidden',
          status: 403,
          detail: 'Access to this resource is forbidden.',
          code: 'FORBIDDEN',
          traceId: 't-6',
        },
      },
    })

    renderDetailPage(['BUDGET_READ'])

    await waitFor(() => {
      expect(screen.getByText(/无法加载承诺/)).toBeInTheDocument()
    })
  })
})
describe('BudgetTotalEditor (total change is a sensitive action)', () => {
  it('budget total can be changed to zero', async () => {
    // Backend contract: total_amount >= 0 rejects only negatives, so
    // 0.00000000 is a legal total. The editor must not treat zero as invalid.
    mockedBudgetApi.get.mockResolvedValue({ ...SENTINEL_BUDGET, version: 7 })
    renderDetailPage(['BUDGET_READ', 'BUDGET_MANAGE'])
    await waitFor(() => expect(screen.getByRole('button', { name: /修改总额/ })).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: /修改总额/ }))
    fireEvent.change(screen.getByLabelText('新的总额'), { target: { value: '0' } })

    expect(screen.getByText('0.00000000 CNY')).toBeInTheDocument()
    const confirm = screen.getByRole('button', { name: /确认修改/ })
    expect(confirm).toBeEnabled()

    fireEvent.click(confirm)

    await waitFor(() => {
      expect(mockedBudgetApi.update).toHaveBeenCalledWith('7', {
        totalAmount: '0.00000000',
        expectedVersion: 7,
      })
    })
    expect(mockedBudgetApi.update).toHaveBeenCalledTimes(1)
  })

  it('still rejects a negative total', async () => {
    mockedBudgetApi.get.mockResolvedValue({ ...SENTINEL_BUDGET, version: 7 })
    renderDetailPage(['BUDGET_READ', 'BUDGET_MANAGE'])
    await waitFor(() => expect(screen.getByRole('button', { name: /修改总额/ })).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: /修改总额/ }))
    fireEvent.change(screen.getByLabelText('新的总额'), { target: { value: '-1' } })

    expect(screen.getByText('总额不能为负数')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /确认修改/ })).toBeDisabled()
    expect(mockedBudgetApi.update).not.toHaveBeenCalled()
  })

  it('shows the total editor only with BUDGET_MANAGE', async () => {
    renderDetailPage(['BUDGET_READ'])
    await waitFor(() => expect(screen.getByText(/预算详情/)).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: /修改总额/ })).not.toBeInTheDocument()

    renderDetailPage(['BUDGET_READ', 'BUDGET_MANAGE'])
    await waitFor(() => expect(screen.getByRole('button', { name: /修改总额/ })).toBeInTheDocument())
  })

  it('shows current total, currency and the normalized new total in the confirm modal', async () => {
    renderDetailPage(['BUDGET_READ', 'BUDGET_MANAGE'])
    await waitFor(() => expect(screen.getByRole('button', { name: /修改总额/ })).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: /修改总额/ }))

    expect(screen.getByText('当前总额')).toBeInTheDocument()
    // '币种' also labels the identity card, so presence is asserted loosely.
    expect(screen.getAllByText('币种').length).toBeGreaterThanOrEqual(1)
    // Typing a short decimal shows the canonical scale-8 before/after preview.
    fireEvent.change(screen.getByLabelText('新的总额'), { target: { value: '150.5' } })
    expect(screen.getByText('150.50000000 CNY')).toBeInTheDocument()
    // Nothing is sent before the confirm button.
    expect(mockedBudgetApi.update).not.toHaveBeenCalled()
  })

  it('sends exactly totalAmount and expectedVersion on confirm', async () => {
    renderDetailPage(['BUDGET_READ', 'BUDGET_MANAGE'])
    await waitFor(() => expect(screen.getByRole('button', { name: /修改总额/ })).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: /修改总额/ }))
    fireEvent.change(screen.getByLabelText('新的总额'), { target: { value: '150.5' } })

    fireEvent.click(screen.getByRole('button', { name: /确认修改/ }))

    await waitFor(() => {
      expect(mockedBudgetApi.update).toHaveBeenCalledWith('7', {
        totalAmount: '150.50000000',
        expectedVersion: 4,
      })
    })
    const body = mockedBudgetApi.update.mock.calls[0][1] as unknown as Record<string, unknown>
    expect(Object.keys(body).sort()).toEqual(['expectedVersion', 'totalAmount'])
  })

  it('refreshes the budget detail and list after a successful total change', async () => {
    mockedBudgetApi.update.mockResolvedValue({ ...SENTINEL_BUDGET, totalAmount: '150.50000000', version: 5 })
    renderDetailPage(['BUDGET_READ', 'BUDGET_MANAGE'])
    await waitFor(() => expect(screen.getByRole('button', { name: /修改总额/ })).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: /修改总额/ }))
    fireEvent.change(screen.getByLabelText('新的总额'), { target: { value: '150.5' } })
    fireEvent.click(screen.getByRole('button', { name: /确认修改/ }))

    await waitFor(() => {
      expect(mockedBudgetApi.get.mock.calls.length).toBeGreaterThanOrEqual(2)
    })
  })

  it('does not auto-retry a 409 state conflict and shows refresh guidance', async () => {
    mockedBudgetApi.update.mockRejectedValue({
      isAxiosError: true,
      response: {
        data: {
          title: 'State conflict',
          status: 409,
          detail: 'The budget changed concurrently.',
          code: 'STATE_CONFLICT',
          traceId: 't-1',
        },
      },
    })
    renderDetailPage(['BUDGET_READ', 'BUDGET_MANAGE'])
    await waitFor(() => expect(screen.getByRole('button', { name: /修改总额/ })).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: /修改总额/ }))
    fireEvent.change(screen.getByLabelText('新的总额'), { target: { value: '150.5' } })
    fireEvent.click(screen.getByRole('button', { name: /确认修改/ }))

    await waitFor(() => {
      expect(screen.getByText('预算已发生变化，请刷新最新版本后重试。')).toBeInTheDocument()
    })
    // The mutation is never re-sent; the latest version is refetched instead.
    expect(mockedBudgetApi.update).toHaveBeenCalledTimes(1)
    await waitFor(() => {
      expect(mockedBudgetApi.get.mock.calls.length).toBeGreaterThanOrEqual(2)
    })
  })
})
describe('RequestCommitment (budget detail)', () => {
  async function openRequestModal() {
    await waitFor(() => expect(screen.getByRole('button', { name: /申请承诺/ })).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: /申请承诺/ }))
    fireEvent.change(screen.getByLabelText('承诺金额'), { target: { value: '60.5' } })
  }

  it('shows the request action only with COMMITMENT_REQUEST', async () => {
    renderDetailPage(['BUDGET_READ'])
    await waitFor(() => expect(screen.getByText(/预算详情/)).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: /申请承诺/ })).not.toBeInTheDocument()

    renderDetailPage(['BUDGET_READ', 'COMMITMENT_REQUEST'])
    await waitFor(() => expect(screen.getByRole('button', { name: /申请承诺/ })).toBeInTheDocument())
  })

  it('sends the budget currency and a canonical scale-8 amount with an Idempotency-Key', async () => {
    mockedCommitmentApi.create.mockResolvedValue({
      id: '10',
      budgetId: '7',
      status: 'REQUESTED',
      requestedAmount: '60.50000000',
      approvedAmount: null,
      remainingAmount: null,
      version: 1,
      createdAt: '2026-01-05T00:00:00Z',
      updatedAt: '2026-01-05T00:00:00Z',
      approvalCaseId: 'c-2',
      approvalStatus: 'PENDING',
      history: [],
    })
    renderDetailPage(['BUDGET_READ', 'COMMITMENT_REQUEST'])
    await openRequestModal()

    fireEvent.click(screen.getByRole('button', { name: /确认申请/ }))

    await waitFor(() => {
      expect(mockedCommitmentApi.create).toHaveBeenCalledWith('7', {
        requestedAmount: '60.50000000',
        currency: 'CNY',
      }, expect.any(String))
    })
    const key = mockedCommitmentApi.create.mock.calls[0][2] as string
    expect(key.length).toBeGreaterThan(0)
  })

  it('refreshes the commitment list after a successful request', async () => {
    mockedCommitmentApi.create.mockResolvedValue({
      id: '10',
      budgetId: '7',
      status: 'REQUESTED',
      requestedAmount: '60.50000000',
      approvedAmount: null,
      remainingAmount: null,
      version: 1,
      createdAt: '2026-01-05T00:00:00Z',
      updatedAt: '2026-01-05T00:00:00Z',
      approvalCaseId: 'c-2',
      approvalStatus: 'PENDING',
      history: [],
    })
    renderDetailPage(['BUDGET_READ', 'COMMITMENT_REQUEST'])
    await openRequestModal()
    fireEvent.click(screen.getByRole('button', { name: /确认申请/ }))

    await waitFor(() => {
      expect(mockedCommitmentApi.list.mock.calls.length).toBeGreaterThanOrEqual(2)
    })
  })

  it('never optimistically changes the budget metrics after a request', async () => {
    mockedCommitmentApi.create.mockResolvedValue({
      id: '10',
      budgetId: '7',
      status: 'REQUESTED',
      requestedAmount: '60.50000000',
      approvedAmount: null,
      remainingAmount: null,
      version: 1,
      createdAt: '2026-01-05T00:00:00Z',
      updatedAt: '2026-01-05T00:00:00Z',
      approvalCaseId: 'c-2',
      approvalStatus: 'PENDING',
      history: [],
    })
    renderDetailPage(['BUDGET_READ', 'COMMITMENT_REQUEST'])
    await openRequestModal()
    fireEvent.click(screen.getByRole('button', { name: /确认申请/ }))

    await waitFor(() => {
      expect(mockedCommitmentApi.list.mock.calls.length).toBeGreaterThanOrEqual(2)
    })
    // Server truth is untouched: committed is still the server value and no
    // requested amount ever appears as a budget metric.
    expect(screen.getByText('20.00000000 CNY')).toBeInTheDocument()
    expect(screen.getByText('48.50000000 CNY')).toBeInTheDocument()
    expect(screen.queryByText('60.50000000 CNY')).not.toBeInTheDocument()
  })

  it('shows the explicit budget insufficient message on 409 without retrying', async () => {
    mockedCommitmentApi.create.mockRejectedValue({
      isAxiosError: true,
      response: {
        data: {
          title: 'Insufficient budget',
          status: 409,
          detail: 'Not enough availability.',
          code: 'BUDGET_INSUFFICIENT',
          traceId: 't-2',
        },
      },
    })
    renderDetailPage(['BUDGET_READ', 'COMMITMENT_REQUEST'])
    await openRequestModal()
    fireEvent.click(screen.getByRole('button', { name: /确认申请/ }))

    await waitFor(() => {
      expect(screen.getByText('预算可用额度不足。')).toBeInTheDocument()
    })
    expect(mockedCommitmentApi.create).toHaveBeenCalledTimes(1)
  })

  it('shows the explicit period-not-open message on 409 without retrying', async () => {
    mockedCommitmentApi.create.mockRejectedValue({
      isAxiosError: true,
      response: {
        data: {
          title: 'Period closed',
          status: 409,
          detail: 'The financial period is closed.',
          code: 'PERIOD_NOT_OPEN',
          traceId: 't-3',
        },
      },
    })
    renderDetailPage(['BUDGET_READ', 'COMMITMENT_REQUEST'])
    await openRequestModal()
    fireEvent.click(screen.getByRole('button', { name: /确认申请/ }))

    await waitFor(() => {
      expect(screen.getByText('当前账期不允许执行此操作。')).toBeInTheDocument()
    })
    expect(mockedCommitmentApi.create).toHaveBeenCalledTimes(1)
  })
})