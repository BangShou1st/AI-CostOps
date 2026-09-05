import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, cleanup } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from '../auth/AuthSessionProvider'
import { periodCloseApi } from '../period-close/api/periodCloseApi'
import { reconciliationApi } from './api/reconciliationApi'
import { ReconciliationPage } from './ReconciliationPage'
import { ReconciliationRunDetailPage } from './ReconciliationRunDetailPage'

vi.mock('../auth/AuthSessionProvider', () => ({ useAuth: vi.fn() }))
vi.mock('../period-close/api/periodCloseApi', () => ({
  periodCloseApi: { listBillingPeriods: vi.fn() },
}))
vi.mock('./api/reconciliationApi', () => ({
  reconciliationApi: {
    listRuns: vi.fn(),
    createRun: vi.fn(),
    getRun: vi.fn(),
    listCases: vi.fn(),
    listRunEvidence: vi.fn(),
    listCaseEvidence: vi.fn(),
    decideChargeDisposition: vi.fn(),
    postCaseAdjustment: vi.fn(),
    postGatewayResolution: vi.fn(),
    linkCorrection: vi.fn(),
  },
}))
vi.mock('../period-close/api/periodCloseApi', () => ({
  periodCloseApi: { listBillingPeriods: vi.fn() },
}))
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>()
  return {
    ...actual,
    useNavigate: () => vi.fn(),
    useParams: () => ({ runId: '7' }),
  }
})

const mockedUseAuth = vi.mocked(useAuth)
const mockedPeriodCloseApi = vi.mocked(periodCloseApi)
const mockedReconciliationApi = vi.mocked(reconciliationApi)

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
    externalAmount: null, internalAmount: null, differenceAmount: null, createdAt: '2026-08-21T01:00:01Z',
  },
]
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
  mockedReconciliationApi.listRuns.mockResolvedValue({ items: [run], page: 0, size: 30, totalElements: 1, totalPages: 1 })
  mockedReconciliationApi.getRun.mockResolvedValue(run)
  mockedReconciliationApi.listCases.mockImplementation(async (params) => ({
    items: [caseRow], page: params.page, size: params.size, totalElements: 120, totalPages: 3,
  }))
  mockedReconciliationApi.listRunEvidence.mockResolvedValue({
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
    expect(screen.getByText(/请求 #42/)).toBeInTheDocument()

    fireEvent.click(screen.getByTitle('2'))
    await waitFor(() => {
      expect(mockedReconciliationApi.listCases).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1, size: 50 }))
    })
  })
})
