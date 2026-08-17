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
    return { title: 'Request failed', status: 0, detail: null, code: 'NETWORK_ERROR', traceId: null }
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
    mockedExpenseApi.getForReview.mockResolvedValue(makeExpense({
      status: 'APPROVED',
      evidenceId: 'ev-1',
      canEdit: false,
      postingReady: false,
    }))
    mockedAllocationApi.listDecisionsByExpense.mockResolvedValue([draft])
    mockedAllocationApi.confirm.mockResolvedValue(makeDecision({ id: 'dec-draft', status: 'CONFIRMED' }))
    // After confirm, the refetched expense reports postingReady = true.
    mockedExpenseApi.getForReview.mockResolvedValue(makeExpense({
      status: 'APPROVED',
      evidenceId: 'ev-1',
      canEdit: false,
      postingReady: true,
      currentAllocationDecisionId: 'dec-draft',
    }))
    mockedExpenseApi.downloadEvidence.mockResolvedValue(new Blob(['x']))

    renderWithRouter(<ExpenseReviewDetailPage />, '/expense-reviews/exp-1')

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '确认分摊' })).toBeEnabled()
    })
    fireEvent.click(screen.getByRole('button', { name: '确认分摊' }))

    await waitFor(() => expect(mockedAllocationApi.confirm).toHaveBeenCalledWith('dec-draft', expect.any(String)))
    await waitFor(() => {
      expect(screen.getByText('发布就绪')).toBeInTheDocument()
    })
    // postingReady flips to true only after the backend refetch.
    await waitFor(() => {
      const readyText = screen.getAllByText('✓')
      expect(readyText.length).toBeGreaterThan(0)
    })
  })

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
      expect(screen.getByText(/Permission is required/)).toBeInTheDocument()
    })
    expect(screen.getByText('EXPENSE_REVIEW is required to review expense allocations.')).toBeInTheDocument()
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