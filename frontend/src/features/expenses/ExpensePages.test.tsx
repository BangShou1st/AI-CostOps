import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import type { AuthUser } from '../auth/authSession'
import { expenseApi, type ExpenseResponse } from './api/expenseApi'
import { allocationApi, type AllocationDecision } from '../allocation/api/allocationApi'
import { ExpenseDetailPage } from './ExpenseDetailPage'
import { ExpenseReviewDetailPage } from './ExpenseReviewDetailPage'
import { ExpenseEvidenceSection } from './components/ExpenseEvidenceSection'
import { ExpensesNewPage } from './ExpensesNewPage'

// -- mocks ------------------------------------------------------------------

let currentUser: AuthUser | null = null

vi.mock('../auth/AuthSessionProvider', () => ({
  useAuth: () => ({ user: currentUser }),
}))

vi.mock('./api/expenseApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./api/expenseApi')>()
  return {
    ...actual,
    expenseApi: {
      create: vi.fn(),
      listMine: vi.fn(),
      get: vi.fn(),
      edit: vi.fn(),
      submit: vi.fn(),
      cancel: vi.fn(),
      uploadEvidence: vi.fn(),
      downloadEvidence: vi.fn(),
      listReviewQueue: vi.fn(),
      getForReview: vi.fn(),
      requestInfo: vi.fn(),
      approve: vi.fn(),
      reject: vi.fn(),
    },
  }
})

vi.mock('../allocation/api/allocationApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../allocation/api/allocationApi')>()
  return {
    ...actual,
    allocationApi: {
      listTargets: vi.fn(),
      listDecisionsByCharge: vi.fn(),
      listDecisionsByExpense: vi.fn(),
      getDecision: vi.fn(),
      createManualDraft: vi.fn(),
      createManualDraftForExpense: vi.fn(),
      replaceLines: vi.fn(),
      confirm: vi.fn(),
      propose: vi.fn(),
    },
  }
})

vi.mock('../../api/problem', () => ({
  problemDetail: vi.fn((problem: { code: string; detail: string | null; status: number }) => (
    problem.code === 'FORBIDDEN'
      ? '您没有访问此资源的权限。如您认为这是误判，请联系管理员。'
      : problem.detail
  )),
  problemTitle: vi.fn((problem: { code: string; title: string; status: number }) => (
    problem.code === 'FORBIDDEN' || problem.status === 403 ? '访问被拒绝' : problem.title
  )),
  problemSummary: vi.fn((problem: { code: string; title: string; status: number }) => (
    `${problem.code === 'FORBIDDEN' || problem.status === 403 ? '访问被拒绝' : problem.title}（${problem.code}）`
  )),
  toProblemDetail: vi.fn((error: unknown) => {
    if (error && typeof error === 'object' && 'response' in error) {
      const axiosError = error as { response: { data: unknown } }
      return axiosError.response.data as {
        title: string
        status: number
        detail: string | null
        code: string
        traceId: string | null
      }
    }
    if (error && typeof error === 'object' && 'title' in error) {
      return error as {
        title: string
        status: number
        detail: string | null
        code: string
        traceId: string | null
      }
    }
    return { title: '请求失败', status: 0, detail: '服务暂时无法连接，请稍后重试。', code: 'NETWORK_ERROR', traceId: null }
  }),
}))

const mockedExpenseApi = vi.mocked(expenseApi)
const mockedAllocationApi = vi.mocked(allocationApi)

// -- fixtures ----------------------------------------------------------------

const EMPLOYEE: AuthUser = {
  id: 'u-1',
  email: 'employee@example.com',
  displayName: 'Employee',
  organizationId: 'org-1',
  organizationMemberId: 'm-1',
  permissions: ['EXPENSE_READ_OWN', 'EXPENSE_CREATE_OWN', 'EXPENSE_SUBMIT_OWN', 'EVIDENCE_UPLOAD_OWN'],
}

const PROJECT_OWNER: AuthUser = {
  ...EMPLOYEE,
  id: 'u-2',
  permissions: [
    'EXPENSE_READ_OWN',
    'ALLOCATION_READ',
    'ALLOCATION_EDIT',
    'ALLOCATION_CONFIRM',
  ],
}

const FINANCE: AuthUser = {
  ...EMPLOYEE,
  id: 'u-3',
  permissions: [
    'EXPENSE_REVIEW',
    'ALLOCATION_READ',
    'ALLOCATION_EDIT',
    'ALLOCATION_CONFIRM',
    'EVIDENCE_READ',
    'EVIDENCE_DOWNLOAD',
  ],
}

function makeExpense(overrides: Partial<ExpenseResponse> = {}): ExpenseResponse {
  return {
    id: 'exp-1',
    status: 'DRAFT',
    claimantMemberId: 'm-1',
    evidenceId: null,
    expenseDate: '2026-08-01',
    amount: '100.00000000',
    currency: 'CNY',
    currentAllocationDecisionId: null,
    approvalCaseId: null,
    approvalStatus: null,
    postingReady: false,
    canEdit: true,
    version: 1,
    createdAt: '2026-08-01T00:00:00Z',
    updatedAt: '2026-08-01T00:00:00Z',
    history: [],
    ...overrides,
  }
}

function makeDecision(overrides: Partial<AllocationDecision> = {}): AllocationDecision {
  return {
    id: 'dec-1',
    subjectType: 'EXPENSE_CLAIM',
    chargeFactId: null,
    expenseClaimId: 'exp-1',
    source: 'MANUAL',
    status: 'DRAFT',
    allocationRule: null,
    createdByMemberId: 'm-3',
    createdAt: '2026-08-01T00:00:00Z',
    lines: [
      { lineIndex: 0, allocatedAmount: '100.00000000', currency: 'CNY', projectId: 'p-1', costCenterId: null, teamId: null },
    ],
    ...overrides,
  }
}

function renderWithRouter(element: React.ReactElement, entry = '/expenses/exp-1') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <Routes>
        <Route path="/expenses/new" element={element} />
        <Route path="/expenses/:expenseId" element={element} />
        <Route path="/expense-reviews/:expenseId" element={element} />
      </Routes>
    </MemoryRouter>,
    { wrapper: ({ children }) => <QueryClientProvider client={queryClient}>{children}</QueryClientProvider> },
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  currentUser = EMPLOYEE
})

afterEach(() => {
  currentUser = null
})

// -- tests ------------------------------------------------------------------

describe('ExpensePages', () => {
  it('employee DRAFT shows submit and evidence upload, but no allocation controls', async () => {
    mockedExpenseApi.get.mockResolvedValue(makeExpense({ status: 'DRAFT', evidenceId: null }))
    mockedExpenseApi.downloadEvidence.mockResolvedValue(new Blob(['x']))

    renderWithRouter(<ExpenseDetailPage />)

    await waitFor(() => {
      expect(screen.getByText(/提交/)).toBeInTheDocument()
    })
    expect(screen.queryByText('手动分摊')).not.toBeInTheDocument()
    expect(screen.getByText('上传凭证')).toBeInTheDocument()
  })

  it('shows the last submit time, derived from the approval history, instead of "-"', async () => {
    // UAT: every SUBMITTED/NEEDS_INFO/CANCELED expense showed 提交时间 "-"
    // although the history contained the SUBMIT instant. The frozen API has no
    // submittedAt field (see openapi.yaml ExpenseResponse), so the detail page
    // must derive it from the last SUBMIT/RESUBMIT action.
    mockedExpenseApi.get.mockResolvedValue(makeExpense({
      status: 'NEEDS_INFO',
      evidenceId: 'ev-1',
      canEdit: false,
      version: 2,
      history: [
        {
          id: 'a1', actionType: 'SUBMIT', actorMemberId: 'm-1',
          fromState: 'DRAFT', toState: 'SUBMITTED', comment: null,
          createdAt: '2026-08-17T10:30:00Z',
        },
        {
          id: 'a2', actionType: 'REQUEST_INFO', actorMemberId: 'm-3',
          fromState: 'SUBMITTED', toState: 'NEEDS_INFO', comment: '请补充发票',
          createdAt: '2026-08-17T11:00:00Z',
        },
      ],
    }))
    mockedExpenseApi.downloadEvidence.mockResolvedValue(new Blob(['x']))

    renderWithRouter(<ExpenseDetailPage />)

    await waitFor(() => expect(screen.getByText('下载凭证')).toBeInTheDocument())
    const row = screen.getByText('提交时间').closest('tr')
    expect(row?.textContent).toContain('2026-08-17 18:30')
    expect(row?.textContent).not.toContain('—')
  })

  it('resubmission updates 提交时间 to the latest RESUBMIT action', async () => {
    // The submit time is the LAST submit action: a NEEDS_INFO -> resubmit
    // flow shows the resubmit instant, not the original SUBMIT.
    mockedExpenseApi.get.mockResolvedValue(makeExpense({
      status: 'SUBMITTED',
      evidenceId: 'ev-1',
      canEdit: false,
      version: 3,
      history: [
        {
          id: 'a1', actionType: 'SUBMIT', actorMemberId: 'm-1',
          fromState: 'DRAFT', toState: 'SUBMITTED', comment: null,
          createdAt: '2026-08-17T10:30:00Z',
        },
        {
          id: 'a2', actionType: 'REQUEST_INFO', actorMemberId: 'm-3',
          fromState: 'SUBMITTED', toState: 'NEEDS_INFO', comment: '请补充发票',
          createdAt: '2026-08-17T11:00:00Z',
        },
        {
          id: 'a3', actionType: 'RESUBMIT', actorMemberId: 'm-1',
          fromState: 'NEEDS_INFO', toState: 'SUBMITTED', comment: null,
          createdAt: '2026-08-17T14:20:00Z',
        },
      ],
    }))
    mockedExpenseApi.downloadEvidence.mockResolvedValue(new Blob(['x']))

    renderWithRouter(<ExpenseDetailPage />)

    await waitFor(() => expect(screen.getByText('下载凭证')).toBeInTheDocument())
    const row = screen.getByText('提交时间').closest('tr')
    expect(row?.textContent).toContain('2026-08-17 22:20')
  })

  it('shows an em dash for 提交时间 when the expense was never submitted', async () => {
    mockedExpenseApi.get.mockResolvedValue(makeExpense({ status: 'DRAFT', evidenceId: null, history: [] }))

    renderWithRouter(<ExpenseDetailPage />)

    await waitFor(() => expect(screen.getByText('上传凭证')).toBeInTheDocument())
    const row = screen.getByText('提交时间').closest('tr')
    expect(row?.textContent).toContain('—')
  })

  it('SUBMITTED employee can download evidence but cannot upload', async () => {
    mockedExpenseApi.get.mockResolvedValue(makeExpense({
      status: 'SUBMITTED',
      evidenceId: 'ev-1',
      canEdit: false,
    }))
    mockedExpenseApi.downloadEvidence.mockResolvedValue(new Blob(['x']))

    renderWithRouter(<ExpenseDetailPage />)

    await waitFor(() => expect(screen.getByText('下载凭证')).toBeInTheDocument())
    expect(screen.queryByText('上传凭证')).not.toBeInTheDocument()
    expect(screen.queryByText('替换凭证')).not.toBeInTheDocument()
  })

  it('APPROVED employee can download evidence but cannot upload', async () => {
    mockedExpenseApi.get.mockResolvedValue(makeExpense({
      status: 'APPROVED',
      evidenceId: 'ev-1',
      canEdit: false,
    }))
    mockedExpenseApi.downloadEvidence.mockResolvedValue(new Blob(['x']))

    renderWithRouter(<ExpenseDetailPage />)

    await waitFor(() => expect(screen.getByText('下载凭证')).toBeInTheDocument())
    expect(screen.queryByText('上传凭证')).not.toBeInTheDocument()
  })

  it('employee with allocation permissions still cannot see allocation controls on own page', async () => {
    currentUser = PROJECT_OWNER
    mockedExpenseApi.get.mockResolvedValue(makeExpense({
      status: 'APPROVED',
      evidenceId: 'ev-1',
      canEdit: false,
    }))
    mockedExpenseApi.downloadEvidence.mockResolvedValue(new Blob(['x']))

    renderWithRouter(<ExpenseDetailPage />)

    await waitFor(() => expect(screen.getByText('下载凭证')).toBeInTheDocument())
    expect(screen.queryByText('手动分摊')).not.toBeInTheDocument()
  })

  it('finance review shows evidence download and no upload', async () => {
    currentUser = FINANCE
    mockedExpenseApi.getForReview.mockResolvedValue(makeExpense({
      status: 'APPROVED',
      evidenceId: 'ev-1',
      canEdit: false,
    }))
    mockedAllocationApi.listDecisionsByExpense.mockResolvedValue([])
    mockedExpenseApi.downloadEvidence.mockResolvedValue(new Blob(['x']))

    renderWithRouter(<ExpenseReviewDetailPage />, '/expense-reviews/exp-1')

    await waitFor(() => expect(screen.getByText('下载凭证')).toBeInTheDocument())
    expect(screen.queryByText('上传凭证')).not.toBeInTheDocument()
    expect(screen.queryByText('替换凭证')).not.toBeInTheDocument()
  })

  it('finance APPROVED expense loads decisions and shows the manual draft in the editor', async () => {
    currentUser = FINANCE
    const draft = makeDecision({ id: 'dec-draft', source: 'MANUAL', status: 'DRAFT' })
    mockedExpenseApi.getForReview.mockResolvedValue(makeExpense({
      status: 'APPROVED',
      evidenceId: 'ev-1',
      canEdit: false,
    }))
    mockedAllocationApi.listDecisionsByExpense.mockResolvedValue([draft])
    mockedExpenseApi.downloadEvidence.mockResolvedValue(new Blob(['x']))

    renderWithRouter(<ExpenseReviewDetailPage />, '/expense-reviews/exp-1')

    await waitFor(() => expect(mockedAllocationApi.listDecisionsByExpense).toHaveBeenCalledWith('exp-1'))
    // The editor receives the draft and renders its lines read-only.
    await waitFor(() => {
      expect(screen.getByLabelText('第 1 行金额')).toBeInTheDocument()
    })
  })

  it('finance confirm path re-reads postingReady from the backend and refreshes', async () => {
    currentUser = FINANCE
    const draft = makeDecision({ id: 'dec-draft', source: 'MANUAL', status: 'DRAFT' })
    const confirmed = makeDecision({ id: 'dec-draft', source: 'MANUAL', status: 'CONFIRMED' })
    // The first backend expense response reports NOT posting-ready; the
    // SECOND response (returned after the confirm refetch) reports ready.
    // mockResolvedValueOnce + mockResolvedValue keeps the initial state
    // genuinely false — a single mockResolvedValue(true) would override the
    // first response and fake the tick from the start.
    mockedExpenseApi.getForReview
      .mockResolvedValueOnce(makeExpense({
        status: 'APPROVED',
        evidenceId: 'ev-1',
        canEdit: false,
        postingReady: false,
      }))
      .mockResolvedValue(makeExpense({
        status: 'APPROVED',
        evidenceId: 'ev-1',
        canEdit: false,
        postingReady: true,
        currentAllocationDecisionId: 'dec-draft',
      }))
    // Decisions are simulated for real as well: DRAFT before confirm,
    // CONFIRMED after the confirm refetch.
    mockedAllocationApi.listDecisionsByExpense
      .mockResolvedValueOnce([draft])
      .mockResolvedValue([confirmed])
    mockedAllocationApi.confirm.mockResolvedValue(confirmed)
    mockedExpenseApi.downloadEvidence.mockResolvedValue(new Blob(['x']))

    renderWithRouter(<ExpenseReviewDetailPage />, '/expense-reviews/exp-1')

    // Initial backend response: posting is NOT ready.
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '确认分摊' })).toBeEnabled()
    })
    expect(screen.getByText('否')).toBeInTheDocument()
    expect(screen.queryByText('✓')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '确认分摊' }))

    // The backend confirm endpoint was called for the manual draft.
    await waitFor(() => expect(mockedAllocationApi.confirm).toHaveBeenCalledWith('dec-draft', expect.any(String)))
    // The tick can only come from the refetched backend expense response:
    // both queries are re-read after the mutation.
    await waitFor(() => {
      expect(screen.getByText('✓')).toBeInTheDocument()
    })
    expect(mockedExpenseApi.getForReview.mock.calls.length).toBeGreaterThanOrEqual(2)
    expect(mockedAllocationApi.listDecisionsByExpense.mock.calls.length).toBeGreaterThanOrEqual(2)
  })

  it('finance creates a manual draft from an empty decision list, refetches, then confirms it', async () => {
    currentUser = FINANCE
    const draft = makeDecision({ id: 'dec-draft', source: 'MANUAL', status: 'DRAFT' })
    const confirmed = makeDecision({ id: 'dec-draft', source: 'MANUAL', status: 'CONFIRMED' })
    mockedExpenseApi.getForReview.mockResolvedValue(makeExpense({
      status: 'APPROVED',
      evidenceId: 'ev-1',
      canEdit: false,
    }))
    // No drafts yet; after the create-draft refetch the backend reports the
    // MANUAL DRAFT. This regression covers the historical bug where the page
    // kept draft={null} after creation and confirm never became available.
    mockedAllocationApi.listDecisionsByExpense
      .mockResolvedValueOnce([])
      .mockResolvedValue([draft])
    mockedAllocationApi.listTargets.mockResolvedValue([
      { type: 'PROJECT', id: 'p-1', name: '平台' },
    ])
    mockedAllocationApi.createManualDraftForExpense.mockResolvedValue(draft)
    mockedAllocationApi.confirm.mockResolvedValue(confirmed)
    mockedExpenseApi.downloadEvidence.mockResolvedValue(new Blob(['x']))

    renderWithRouter(<ExpenseReviewDetailPage />, '/expense-reviews/exp-1')

    // The page starts with no draft: confirm exists but is not executable.
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '创建分摊草稿' })).toBeInTheDocument()
    })
    // Once the create-draft mutation has run, the shared loading state leaves
    // antd's loading icon (aria-label="loading") on the confirm button; jsdom
    // never fires the CSS transition end, so the icon never leaves the DOM and
    // the accessible name becomes "loading 确认分摊". The regex match keeps
    // the query working in both pre- and post-mutation states.
    expect(screen.getByRole('button', { name: /确认分摊/ })).toBeDisabled()

    fireEvent.click(screen.getByRole('button', { name: '添加分摊行' }))
    fireEvent.change(screen.getByLabelText('第 1 行金额'), { target: { value: '100.00000000' } })

    await screen.findByRole('option', { name: '平台' })
    fireEvent.change(screen.getByLabelText('第 1 行分摊对象'), { target: { value: 'PROJECT:p-1' } })

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '创建分摊草稿' })).toBeEnabled()
    })
    fireEvent.click(screen.getByRole('button', { name: '创建分摊草稿' }))

    await waitFor(() => expect(mockedAllocationApi.createManualDraftForExpense).toHaveBeenCalledWith(
      'exp-1',
      [{ allocatedAmount: '100.00000000', currency: 'CNY', projectId: 'p-1', costCenterId: null, teamId: null }],
      expect.any(String),
    ))
    // The refetch picked up the created MANUAL DRAFT: the editor recognizes
    // the draft (button switches to 保存分摊) and confirm becomes executable.
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /确认分摊/ })).toBeEnabled()
    })
    expect(screen.getByRole('button', { name: /保存分摊/ })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /确认分摊/ }))
    await waitFor(() => expect(mockedAllocationApi.confirm).toHaveBeenCalledWith('dec-draft', expect.any(String)))
    // This test walks the full create-draft -> refetch -> confirm workflow
    // through jsdom + antd; on slow CI runners it takes ~8s, so it gets an
    // explicit timeout instead of the 5s default.
  }, 15_000)

  it('finance creates an expense manual draft from a short-decimal amount (real UAT flow)', async () => {
    // The UAT operator typed 129.5 (not 129.50000000) for Expense 1. The
    // request must still leave the browser with the canonical scale-8 amount.
    currentUser = FINANCE
    const draft = makeDecision({
      id: 'dec-draft',
      lines: [{ lineIndex: 0, allocatedAmount: '129.50000000', currency: 'CNY', projectId: 'p-1', costCenterId: null, teamId: null }],
    })
    mockedExpenseApi.getForReview.mockResolvedValue(makeExpense({
      status: 'APPROVED',
      evidenceId: 'ev-1',
      canEdit: false,
      amount: '129.50000000',
    }))
    mockedAllocationApi.listDecisionsByExpense.mockResolvedValue([])
    mockedAllocationApi.listTargets.mockResolvedValue([{ type: 'PROJECT', id: 'p-1', name: 'UAT Project' }])
    mockedAllocationApi.createManualDraftForExpense.mockResolvedValue(draft)
    mockedExpenseApi.downloadEvidence.mockResolvedValue(new Blob(['x']))

    renderWithRouter(<ExpenseReviewDetailPage />, '/expense-reviews/exp-1')

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '创建分摊草稿' })).toBeInTheDocument()
    })
    fireEvent.click(screen.getByRole('button', { name: '添加分摊行' }))
    fireEvent.change(screen.getByLabelText('第 1 行金额'), { target: { value: '129.5' } })

    await screen.findByRole('option', { name: 'UAT Project' })
    fireEvent.change(screen.getByLabelText('第 1 行分摊对象'), { target: { value: 'PROJECT:p-1' } })

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '创建分摊草稿' })).toBeEnabled()
    })
    fireEvent.click(screen.getByRole('button', { name: '创建分摊草稿' }))

    await waitFor(() => expect(mockedAllocationApi.createManualDraftForExpense).toHaveBeenCalledWith(
      'exp-1',
      [{ allocatedAmount: '129.50000000', currency: 'CNY', projectId: 'p-1', costCenterId: null, teamId: null }],
      expect.any(String),
    ))
  })

  it('finance sees the CONFIRMED manual allocation as read-only lines', async () => {
    // Real UAT state: Expense 1 APPROVED, decision 1 MANUAL CONFIRMED,
    // line 129.50000000 CNY -> project UAT-PROJECT (id p-1), postingReady.
    // The 手动分摊 area must render the confirmed lines, never "尚无分摊行",
    // and must not offer to create or confirm anything again.
    currentUser = FINANCE
    const confirmed = makeDecision({
      id: 'dec-1',
      source: 'MANUAL',
      status: 'CONFIRMED',
      lines: [{ lineIndex: 0, allocatedAmount: '129.50000000', currency: 'CNY', projectId: 'p-1', costCenterId: null, teamId: null }],
    })
    mockedExpenseApi.getForReview.mockResolvedValue(makeExpense({
      status: 'APPROVED',
      evidenceId: 'ev-1',
      canEdit: false,
      amount: '129.50000000',
      postingReady: true,
      currentAllocationDecisionId: 'dec-1',
    }))
    mockedAllocationApi.listDecisionsByExpense.mockResolvedValue([confirmed])
    mockedAllocationApi.listTargets.mockResolvedValue([{ type: 'PROJECT', id: 'p-1', name: 'UAT Project' }])
    mockedExpenseApi.downloadEvidence.mockResolvedValue(new Blob(['x']))

    renderWithRouter(<ExpenseReviewDetailPage />, '/expense-reviews/exp-1')

    // The confirmed line is displayed and locked.
    await waitFor(() => {
      expect(screen.getByLabelText('第 1 行金额')).toHaveValue('129.50000000')
    })
    expect((screen.getByLabelText('第 1 行金额') as HTMLInputElement).disabled).toBe(true)
    await screen.findByRole('option', { name: 'UAT Project' })
    expect(screen.getByLabelText('第 1 行分摊对象')).toHaveValue('PROJECT:p-1')
    // Not an empty allocation.
    expect(screen.queryByText('尚无分摊行')).not.toBeInTheDocument()
    // Sums read the confirmed lines, not an empty editor.
    expect(screen.getByText(/已分配：129.50000000 CNY/)).toBeInTheDocument()
    expect(screen.getByText(/精确分配/)).toBeInTheDocument()
    // The confirmed state is final: no new draft, no confirm action.
    expect(screen.queryByRole('button', { name: '创建分摊草稿' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '添加分摊行' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /确认分摊/ })).toBeDisabled()
    // Integration-heavy: React Query target resolution + AntD table read-only
    // render; CI runners twice exceeded the 5s default (~5.1-5.6s), so this
    // test gets a local budget instead. This is not a relaxed assertion.
  }, 10_000)

  it('displays the backend problem detail on a 409 conflict', async () => {
    currentUser = FINANCE
    const draft = makeDecision({ id: 'dec-draft', source: 'MANUAL', status: 'DRAFT' })
    mockedExpenseApi.getForReview.mockResolvedValue(makeExpense({
      status: 'APPROVED',
      evidenceId: 'ev-1',
      canEdit: false,
    }))
    mockedAllocationApi.listDecisionsByExpense.mockResolvedValue([draft])
    mockedAllocationApi.confirm.mockRejectedValue({
      response: {
        data: {
          title: 'Allocation sum mismatch',
          status: 409,
          detail: 'The lines must exactly sum to the expense amount.',
          code: 'ALLOCATION_SUM_MISMATCH',
          traceId: 't-1',
        },
      },
    })
    mockedExpenseApi.downloadEvidence.mockResolvedValue(new Blob(['x']))

    renderWithRouter(<ExpenseReviewDetailPage />, '/expense-reviews/exp-1')

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '确认分摊' })).toBeEnabled()
    })
    fireEvent.click(screen.getByRole('button', { name: '确认分摊' }))

    await waitFor(() => {
      expect(screen.getByText(/Allocation sum mismatch/)).toBeInTheDocument()
    })
    expect(screen.getByText('The lines must exactly sum to the expense amount.')).toBeInTheDocument()
  })

  it('displays the backend problem detail on a 403 forbidden', async () => {
    currentUser = FINANCE
    const draft = makeDecision({ id: 'dec-draft', source: 'MANUAL', status: 'DRAFT' })
    mockedExpenseApi.getForReview.mockResolvedValue(makeExpense({
      status: 'APPROVED',
      evidenceId: 'ev-1',
      canEdit: false,
    }))
    mockedAllocationApi.listDecisionsByExpense.mockResolvedValue([draft])
    // Confirm is refused because the actor lacks the required permission.
    mockedAllocationApi.confirm.mockRejectedValue({
      response: {
        data: {
          title: 'Permission is required',
          status: 403,
          detail: 'EXPENSE_REVIEW is required to review expense allocations.',
          code: 'FORBIDDEN',
          traceId: 't-2',
        },
      },
    })
    mockedExpenseApi.downloadEvidence.mockResolvedValue(new Blob(['x']))

    renderWithRouter(<ExpenseReviewDetailPage />, '/expense-reviews/exp-1')

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '确认分摊' })).toBeEnabled()
    })
    fireEvent.click(screen.getByRole('button', { name: '确认分摊' }))

    await waitFor(() => {
      expect(screen.getByText(/访问被拒绝（FORBIDDEN）/)).toBeInTheDocument()
    })
    expect(screen.getByText('您没有访问此资源的权限。如您认为这是误判，请联系管理员。')).toBeInTheDocument()
  })
})

describe('Employee expense mutations', () => {
  // antd inserts a space between two Chinese characters in Button text, so
  // two-character button names are matched with a whitespace-tolerant regex.
  it('create: employee fills the form and expenseApi.create is called with an Idempotency-Key', async () => {
    mockedExpenseApi.create.mockResolvedValue(makeExpense({ id: 'exp-new' }))

    renderWithRouter(<ExpensesNewPage />, '/expenses/new')

    const amountInput = await screen.findByRole('spinbutton')
    fireEvent.change(amountInput, { target: { value: '100' } })

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /^创\s*建$/ })).toBeEnabled()
    })
    fireEvent.click(screen.getByRole('button', { name: /^创\s*建$/ }))

    await waitFor(() => expect(mockedExpenseApi.create).toHaveBeenCalledTimes(1))
    const [body, idempotencyKey] = mockedExpenseApi.create.mock.calls[0]
    expect(body.amount).toBe('100.00000000')
    expect(body.currency).toBe('CNY')
    expect(body.expenseDate).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    expect(idempotencyKey).toMatch(/^[0-9a-f-]{36}$/i)
  })

  it('edit: saving a DRAFT expense calls expenseApi.edit with the expected version', async () => {
    mockedExpenseApi.get.mockResolvedValue(makeExpense({ status: 'DRAFT' }))

    renderWithRouter(<ExpenseDetailPage />)

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /^保\s*存$/ })).toBeInTheDocument()
    })
    fireEvent.click(screen.getByRole('button', { name: /^保\s*存$/ }))

    await waitFor(() => expect(mockedExpenseApi.edit).toHaveBeenCalledTimes(1))
    const [expenseId, body] = mockedExpenseApi.edit.mock.calls[0]
    expect(expenseId).toBe('exp-1')
    expect(body).toEqual({
      expenseDate: '2026-08-01',
      amount: '100.00000000',
      currency: 'CNY',
      expectedVersion: 1,
    })
  })

  it('submit: submitting a DRAFT expense with evidence calls expenseApi.submit with version and key', async () => {
    mockedExpenseApi.get.mockResolvedValue(makeExpense({ status: 'DRAFT', evidenceId: 'ev-1' }))

    renderWithRouter(<ExpenseDetailPage />)

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /^提\s*交$/ })).toBeEnabled()
    })
    fireEvent.click(screen.getByRole('button', { name: /^提\s*交$/ }))

    await waitFor(() => expect(mockedExpenseApi.submit).toHaveBeenCalledTimes(1))
    const [expenseId, body, idempotencyKey] = mockedExpenseApi.submit.mock.calls[0]
    expect(expenseId).toBe('exp-1')
    expect(body).toEqual({ expectedVersion: 1 })
    expect(idempotencyKey).toMatch(/^[0-9a-f-]{36}$/i)
  })

  it('resubmit: NEEDS_INFO with evidence resubmits through the same submit endpoint', async () => {
    mockedExpenseApi.get.mockResolvedValue(makeExpense({ status: 'NEEDS_INFO', evidenceId: 'ev-1', version: 2 }))

    renderWithRouter(<ExpenseDetailPage />)

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '重新提交' })).toBeEnabled()
    })
    fireEvent.click(screen.getByRole('button', { name: '重新提交' }))

    // No new endpoint: the re-submit reuses expenseApi.submit.
    await waitFor(() => expect(mockedExpenseApi.submit).toHaveBeenCalledTimes(1))
    expect(mockedExpenseApi.submit.mock.calls[0][0]).toBe('exp-1')
    expect(mockedExpenseApi.submit.mock.calls[0][1]).toEqual({ expectedVersion: 2 })
  })

  it('cancel: cancelling a SUBMITTED expense confirms the modal then calls expenseApi.cancel', async () => {
    mockedExpenseApi.get.mockResolvedValue(makeExpense({ status: 'SUBMITTED', evidenceId: 'ev-1', canEdit: false, version: 3 }))

    renderWithRouter(<ExpenseDetailPage />)

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /^取\s*消$/ })).toBeInTheDocument()
    })
    fireEvent.click(screen.getByRole('button', { name: /^取\s*消$/ }))

    await waitFor(() => {
      expect(screen.getByText('确定取消这笔报销吗？')).toBeInTheDocument()
    })
    // Without the zhCN ConfigProvider the modal footer is the antd English
    // default: the confirm action is the OK button.
    fireEvent.click(screen.getByRole('button', { name: 'OK' }))

    await waitFor(() => expect(mockedExpenseApi.cancel).toHaveBeenCalledTimes(1))
    const [expenseId, body, idempotencyKey] = mockedExpenseApi.cancel.mock.calls[0]
    expect(expenseId).toBe('exp-1')
    expect(body).toEqual({ expectedVersion: 3 })
    expect(idempotencyKey).toMatch(/^[0-9a-f-]{36}$/i)
  })
})

describe('ExpenseEvidenceSection', () => {
  it('download triggers an authenticated blob download via expenseApi and revokes the object URL', async () => {
    const downloadEvidence = vi.fn().mockResolvedValue(new Blob(['receipt']))
    mockedExpenseApi.downloadEvidence = downloadEvidence
    const objectURLSpy = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:fake')
    const revokeSpy = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})

    render(
      <ExpenseEvidenceSection
        mode="employee"
        canUpload={false}
        expenseId="exp-1"
        evidenceId="ev-1"
        expectedVersion={1}
        onChanged={vi.fn()}
      />,
    )

    const downloadButton = await screen.findByText('下载凭证')
    fireEvent.click(downloadButton)

    await waitFor(() => expect(downloadEvidence).toHaveBeenCalledWith('exp-1'))
    expect(objectURLSpy).toHaveBeenCalled()
    expect(revokeSpy).toHaveBeenCalledWith('blob:fake')

    objectURLSpy.mockRestore()
    revokeSpy.mockRestore()
  })
})
