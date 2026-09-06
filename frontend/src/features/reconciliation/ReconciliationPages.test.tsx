import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, cleanup } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from '../auth/AuthSessionProvider'
import { periodCloseApi } from '../period-close/api/periodCloseApi'
import { reconciliationApi } from './api/reconciliationApi'
import { ReconciliationPage } from './ReconciliationPage'
import { ReconciliationRunDetailPage } from './ReconciliationRunDetailPage'
import { ReconciliationCaseDetailPage } from './ReconciliationCaseDetailPage'
import { allocationApi } from '../allocation/api/allocationApi'

vi.mock('../auth/AuthSessionProvider', () => ({ useAuth: vi.fn() }))
vi.mock('../period-close/api/periodCloseApi', () => ({
  periodCloseApi: { listBillingPeriods: vi.fn() },
}))
vi.mock('./api/reconciliationApi', () => ({
  reconciliationApi: {
    listRuns: vi.fn(),
    createRun: vi.fn(),
    getRun: vi.fn(),
    getCase: vi.fn(),
    listCases: vi.fn(),
    investigateCase: vi.fn(),
    returnCaseToOpen: vi.fn(),
    resolveCase: vi.fn(),
    listRunEvidence: vi.fn(),
    listCaseEvidence: vi.fn(),
    decideChargeDisposition: vi.fn(),
    postCaseAdjustment: vi.fn(),
    postGatewayResolution: vi.fn(),
    linkCorrection: vi.fn(),
  },
}))
vi.mock('../allocation/api/allocationApi', () => ({
  allocationApi: { listTargets: vi.fn() },
}))
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>()
  return {
    ...actual,
    useNavigate: () => vi.fn(),
    useParams: () => ({ runId: '7', caseId: '9' }),
  }
})

const mockedUseAuth = vi.mocked(useAuth)
const mockedPeriodCloseApi = vi.mocked(periodCloseApi)
const mockedReconciliationApi = vi.mocked(reconciliationApi)
const mockedAllocationApi = vi.mocked(allocationApi)

const period = {
  id: '10', periodStart: '2026-08-01', periodEnd: '2026-09-01', status: 'OPEN' as const, version: 0,
}
const run = {
  id: '7', billingPeriodId: '10', status: 'COMPLETED' as const, algorithmVersion: 'M15_HYBRID_PERIOD_PROVIDER_CURRENCY_V2',
  toleranceAmount: '0.00000000', basisHash: 'hash',
  summary: { totalKeys: 3, matchedCount: 2, discrepancyCount: 1, exactEvidenceCount: 2, unresolvedGatewayCount: 1 },
  createdByMemberId: '3', startedAt: '2026-08-21T01:00:00Z', finishedAt: '2026-08-21T01:00:01Z',
  errorCode: null, errorSummary: null, createdAt: '2026-08-21T01:00:00Z', updatedAt: '2026-08-21T01:00:01Z',
}
const evidenceRows = [
  {
    id: '11', reconciliationRunId: '7', reconciliationCaseId: null, evidenceKey: 'GATEWAY_UNRESOLVED:REQUEST:42',
    providerAccountId: '5', currency: 'USD', matchKind: 'GATEWAY_UNRESOLVED' as const, differenceKind: null,
    chargeFactId: null, gatewayRequestId: '42', gatewayRouteAttemptId: '43', gatewayUsageFactId: null,
    gatewaySettlementId: null, correctionGroupId: null, reconciliationAdjustmentId: null,
    gatewayFinancialResolutionId: null, ledgerPostingId: null, providerRequestId: null,
    evidenceReference: null,
    externalAmount: null, internalAmount: null, differenceAmount: null, createdAt: '2026-08-21T01:00:01Z',
  },
]
const exactEvidenceRow = {
  id: '12', reconciliationRunId: '7', reconciliationCaseId: null, evidenceKey: 'EXACT:CHARGE:33:REQUEST:42',
  providerAccountId: '5', currency: 'USD', matchKind: 'EXACT_PROVIDER_REQUEST' as const, differenceKind: null,
  chargeFactId: '33', gatewayRequestId: '42', gatewayRouteAttemptId: '43', gatewayUsageFactId: null,
  gatewaySettlementId: null, correctionGroupId: null, reconciliationAdjustmentId: null,
  gatewayFinancialResolutionId: null, ledgerPostingId: null, providerRequestId: 'prov-req-1',
  evidenceReference: null,
  externalAmount: null, internalAmount: null, differenceAmount: null, createdAt: '2026-08-21T01:00:01Z',
}
const caseRow = {
  id: '9', reconciliationRunId: '7', providerAccountId: '5', currency: 'USD', caseType: 'AMOUNT_MISMATCH',
  externalAmount: '0.00000001', internalAmount: '0.00000000', differenceAmount: '0.00000001',
  externalRowCount: 1, internalRowCount: 1, status: 'OPEN' as const, reasonCode: null,
  resolutionNote: null, resolvedByMemberId: null, resolvedAt: null,
  createdAt: '2026-08-21T01:00:01Z', updatedAt: '2026-08-21T01:00:01Z',
}

function renderPage(page: 'list' | 'detail', permissions: string[] = ['RECONCILIATION_READ']) {
  mockedUseAuth.mockReturnValue({
    status: 'authenticated',
    user: { id: '1', email: 'finance@example.com', displayName: 'Finance', organizationId: '2', organizationMemberId: '3', permissions },
    login: vi.fn(), refreshMe: vi.fn(), logout: vi.fn(),
  } as ReturnType<typeof useAuth>)
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      {page === 'list' ? <ReconciliationPage /> : <ReconciliationRunDetailPage />}
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  mockedPeriodCloseApi.listBillingPeriods.mockResolvedValue([period])
  mockedReconciliationApi.getCase.mockResolvedValue(caseRow)
  mockedAllocationApi.listTargets.mockResolvedValue([{ type: 'PROJECT' as const, id: '4', name: 'Alpha' }])
  mockedReconciliationApi.listRuns.mockResolvedValue({ items: [run], page: 0, size: 30, totalElements: 1, totalPages: 1 })
  mockedReconciliationApi.getRun.mockResolvedValue(run)
  mockedReconciliationApi.listCases.mockImplementation(async (params) => ({
    items: [caseRow], page: params.page, size: params.size, totalElements: 120, totalPages: 3,
  }))
  mockedReconciliationApi.listRunEvidence.mockResolvedValue({
    items: evidenceRows, page: 0, size: 50, totalElements: 1, totalPages: 1,
  })
  mockedReconciliationApi.listCaseEvidence.mockResolvedValue({
    items: evidenceRows, page: 0, size: 50, totalElements: 1, totalPages: 1,
  })
})

afterEach(() => cleanup())

describe('ReconciliationPage', () => {
  it('uses the canonical discrepancy count and removes the unsupported provider filter', async () => {
    renderPage('list')

    expect(await screen.findByText('M15_HYBRID_PERIOD_PROVIDER_CURRENCY_V2')).toBeInTheDocument()
    expect(screen.getAllByText('1').length).toBeGreaterThan(0)
    expect(screen.queryByLabelText('供应商账号')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '运行对账' })).not.toBeInTheDocument()
  })
})

describe('ReconciliationRunDetailPage', () => {
  it('renders canonical counts, exact small money, and requests the next case page', async () => {
    renderPage('detail')

    expect((await screen.findAllByText('0.00000001 USD')).length).toBeGreaterThan(0)
    expect(screen.getByText('匹配键总数')).toBeInTheDocument()
    expect(screen.getByText('M15_HYBRID_PERIOD_PROVIDER_CURRENCY_V2')).toBeInTheDocument()
    expect(screen.getByText('精确请求证据')).toBeInTheDocument()
    expect(screen.getByText('未决网关财务工作')).toBeInTheDocument()
    expect(screen.getAllByText('2').length).toBeGreaterThan(0)
    expect(screen.getAllByText('1').length).toBeGreaterThan(0)
    expect(screen.getAllByText('3').length).toBeGreaterThan(0)
    expect(screen.queryByText(/新鲜度|基准数据已变化|基准数据新鲜/)).not.toBeInTheDocument()

    expect(await screen.findByText('未决网关财务工作（运行级）')).toBeInTheDocument()
    expect(screen.getByText('#42')).toBeInTheDocument()
    expect(screen.getByText('运行级（无案例）')).toBeInTheDocument()

    fireEvent.click(screen.getByTitle('2'))
    await waitFor(() => {
      expect(mockedReconciliationApi.listCases).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1, size: 50 }))
    })
  })

  it('resolves run-level case_id=null gateway work without a fabricated case or client amount', async () => {
    renderPage('detail', ['RECONCILIATION_READ', 'RECONCILIATION_RESOLVE', 'LEDGER_CORRECT'])

    // The case-null GATEWAY_UNRESOLVED item exposes a resolve action.
    expect(await screen.findByText('未决网关财务工作（运行级）')).toBeInTheDocument()
    expect(await screen.findByText('运行级（无案例）')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /处\s*理/ }))
    expect(await screen.findByText('网关财务处理（请求 #42）')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('证明出处'), { target: { value: 'portal-case-4711' } })
    fireEvent.change(screen.getByLabelText('网关处理说明'), { target: { value: 'Provider confirmed no charge' } })
    mockedReconciliationApi.postGatewayResolution.mockResolvedValue({
      id: '78', runId: '7', caseId: null, requestId: '42',
      resolutionType: 'NO_CHARGE_CONFIRMED', reservationOutcome: 'NONE', adjustmentId: null,
    })
    fireEvent.click(screen.getByRole('button', { name: '确认提交' }))
    await waitFor(() => expect(mockedReconciliationApi.postGatewayResolution).toHaveBeenCalled())
    expect(mockedReconciliationApi.postGatewayResolution.mock.calls[0][0]).toBe('7')
    const body = mockedReconciliationApi.postGatewayResolution.mock.calls[0][1]
    expect(body.caseId ?? null).toBeNull()
    expect(body.requestId).toBe('42')
    expect(body.resolutionType).toBe('NO_CHARGE_CONFIRMED')
    expect(body.positiveEvidenceReference).toBe('portal-case-4711')
    // No client financial truth is ever submitted.
    expect(body).not.toHaveProperty('adjustmentAmount')
    expect(body).not.toHaveProperty('commitmentId')
    expect(body.statementChargeFactId ?? null).toBeNull()
  })
})


function renderCasePage(permissions: string[] = ['RECONCILIATION_READ']) {
  mockedUseAuth.mockReturnValue({
    status: 'authenticated',
    user: { id: '1', email: 'finance@example.com', displayName: 'Finance', organizationId: '2', organizationMemberId: '3', permissions },
    login: vi.fn(), refreshMe: vi.fn(), logout: vi.fn(),
  } as ReturnType<typeof useAuth>)
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <ReconciliationCaseDetailPage />
    </QueryClientProvider>,
  )
}

describe('ReconciliationCaseDetailPage', () => {
  it('survives the loading-to-loaded transition without a Rules-of-Hooks violation', async () => {
    let resolveCase!: (value: typeof caseRow) => void
    mockedReconciliationApi.getCase.mockImplementation(
      () => new Promise((resolve) => { resolveCase = resolve }),
    )
    const renderResult = renderCasePage()

    // Initial render: the case detail query is still pending. All hooks must
    // already have run so the deferred resolution cannot change hook order.
    await waitFor(() => expect(mockedReconciliationApi.listCaseEvidence).toHaveBeenCalled())
    resolveCase(caseRow)

    expect(await screen.findByText('对账案例详情')).toBeInTheDocument()
    expect(await screen.findByText('混合证据与单条证据操作')).toBeInTheDocument()
    expect(renderResult.container.textContent).not.toContain('Rendered fewer hooks')
  })

  it('separates whole-case actions from evidence-item actions and never submits an amount for gateway resolution', async () => {
    renderCasePage(['RECONCILIATION_READ', 'RECONCILIATION_RESOLVE', 'LEDGER_CORRECT'])

    // Whole-case action area exists and the server-required difference is shown.
    expect(await screen.findByText('整体案例操作')).toBeInTheDocument()
    expect(screen.getByText(/服务端要求的调整金额/)).toBeInTheDocument()

    // Evidence-item action for the unresolved gateway request.
    fireEvent.click(await screen.findByRole('button', { name: '处理网关财务工作' }))
    const modalTitle = await screen.findByText('网关财务处理（请求 #42）')
    expect(modalTitle).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('证明出处'), { target: { value: 'portal-case-4711' } })
    fireEvent.change(screen.getByLabelText('网关处理说明'), { target: { value: 'Provider confirmed no charge' } })
    mockedReconciliationApi.postGatewayResolution.mockResolvedValue({
      id: '77', runId: '7', caseId: '9', requestId: '42',
      resolutionType: 'NO_CHARGE_CONFIRMED', reservationOutcome: 'NONE', adjustmentId: null,
    })
    fireEvent.click(screen.getByRole('button', { name: '确认提交' }))
    await waitFor(() => expect(mockedReconciliationApi.postGatewayResolution).toHaveBeenCalled())
    const body = mockedReconciliationApi.postGatewayResolution.mock.calls[0][1]
    expect(body.resolutionType).toBe('NO_CHARGE_CONFIRMED')
    expect(body.positiveEvidenceReference).toBe('portal-case-4711')
    // The UI never submits an authoritative adjustment amount.
    expect(body).not.toHaveProperty('adjustmentAmount')
    expect(body).not.toHaveProperty('commitmentId')
    // Resolving one request must not resolve the whole case.
    expect(mockedReconciliationApi.resolveCase).not.toHaveBeenCalled()
  })

  it('derives the exact statement binding on the server and never declares a classification', async () => {
    mockedReconciliationApi.listCaseEvidence.mockResolvedValue({
      items: [...evidenceRows, exactEvidenceRow], page: 0, size: 50, totalElements: 2, totalPages: 1,
    })
    renderCasePage(['RECONCILIATION_READ', 'RECONCILIATION_RESOLVE', 'LEDGER_CORRECT'])

    fireEvent.click(await screen.findByRole('button', { name: '处理网关财务工作' }))
    await screen.findByText('网关财务处理（请求 #42）')
    // The exact correlation is displayed; the reviewer never re-enters the
    // charge or a binding classification.
    expect(screen.getAllByText(/精确请求关联（费用 #33/).length).toBeGreaterThan(0)
    // Switch the select to the statement type.
    fireEvent.mouseDown(screen.getByLabelText('网关处理类型'))
    fireEvent.click(await screen.findByText('STATEMENT_ADJUSTMENT_POSTED（按账单调整）'))
    fireEvent.change(screen.getByLabelText('业务原因代码'), { target: { value: 'REVIEWED_EXACT_LINE' } })
    fireEvent.change(screen.getByLabelText('网关处理说明'), { target: { value: 'Exact correlation reviewed' } })
    mockedReconciliationApi.postGatewayResolution.mockResolvedValue({
      id: '79', runId: '7', caseId: '9', requestId: '42',
      resolutionType: 'STATEMENT_ADJUSTMENT_POSTED', reservationOutcome: 'FINALIZED',
      adjustmentId: '55',
    })
    fireEvent.click(screen.getByRole('button', { name: '确认提交' }))
    await waitFor(() => expect(mockedReconciliationApi.postGatewayResolution).toHaveBeenCalled())
    const body = mockedReconciliationApi.postGatewayResolution.mock.calls[0][1]
    expect(body.resolutionType).toBe('STATEMENT_ADJUSTMENT_POSTED')
    // Server-derived exact binding: the client does not re-declare the charge.
    expect(body.statementChargeFactId ?? null).toBeNull()
    expect(body.reasonCode).toBe('REVIEWED_EXACT_LINE')
    expect(body.reasonCode).not.toBe('MANUAL_BINDING')
    expect(body.reasonCode).not.toBe('EXACT_PROVIDER_REQUEST')
  })

  it('requires a reviewed statement charge id when no exact evidence exists', async () => {
    renderCasePage(['RECONCILIATION_READ', 'RECONCILIATION_RESOLVE', 'LEDGER_CORRECT'])

    fireEvent.click(await screen.findByRole('button', { name: '处理网关财务工作' }))
    await screen.findByText('网关财务处理（请求 #42）')
    fireEvent.mouseDown(screen.getByLabelText('网关处理类型'))
    fireEvent.click(await screen.findByText('STATEMENT_ADJUSTMENT_POSTED（按账单调整）'))
    fireEvent.change(screen.getByLabelText('业务原因代码'), { target: { value: 'REVIEWED_STATEMENT_LINE' } })
    fireEvent.change(screen.getByLabelText('网关处理说明'), { target: { value: 'Reviewed statement line' } })
    // Without exact evidence the submit stays disabled until the reviewer
    // binds a statement charge id.
    const submit = screen.getByRole('button', { name: '确认提交' }) as HTMLButtonElement
    expect(submit).toBeDisabled()
    fireEvent.change(screen.getByLabelText('账单费用编号'), { target: { value: '33' } })
    expect(submit).not.toBeDisabled()
    mockedReconciliationApi.postGatewayResolution.mockResolvedValue({
      id: '80', runId: '7', caseId: '9', requestId: '42',
      resolutionType: 'STATEMENT_ADJUSTMENT_POSTED', reservationOutcome: 'FINALIZED',
      adjustmentId: '56',
    })
    fireEvent.click(submit)
    await waitFor(() => expect(mockedReconciliationApi.postGatewayResolution).toHaveBeenCalled())
    expect(mockedReconciliationApi.postGatewayResolution.mock.calls[0][1].statementChargeFactId).toBe('33')
  })

  it('hides financial actions without permissions while keeping read-only evidence', async () => {
    renderCasePage(['RECONCILIATION_READ'])

    expect(await screen.findByText('混合证据与单条证据操作')).toBeInTheDocument()
    expect(screen.queryByText('整体案例操作')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '处理网关财务工作' })).not.toBeInTheDocument()
    expect(screen.getByText(/无处理权限|需要 RECONCILIATION_RESOLVE/)).toBeInTheDocument()
  })
})
