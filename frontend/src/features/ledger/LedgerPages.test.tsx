import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { useAuth } from '../auth/AuthSessionProvider'
import { LedgerEntryDetailPage } from './LedgerEntryDetailPage'
import { LedgerListPage } from './LedgerListPage'
import { LedgerPostingDetailPage } from './LedgerPostingDetailPage'
import { ledgerApi } from './api/ledgerApi'

vi.mock('../auth/AuthSessionProvider', () => ({ useAuth: vi.fn() }))
vi.mock('./api/ledgerApi', () => ({ ledgerApi: {
  listPostings: vi.fn(), getPosting: vi.fn(), getEntry: vi.fn(), listEntries: vi.fn(), correct: vi.fn(),
} }))
vi.mock('../budgets/api/billingPeriodApi', () => ({ billingPeriodApi: { list: vi.fn() }, billingPeriodKeys: { list: () => ['billing-period'] } }))

const mockedUseAuth = vi.mocked(useAuth)
const mockedLedgerApi = vi.mocked(ledgerApi)

const ENTRY = {
  id: '901', postingId: '900', entryIndex: 0, entryType: 'COST' as const, amount: '10.00000000', currency: 'CNY',
  targetType: 'PROJECT' as const, targetId: '77', budgetId: null, sourceChargeFactId: '31', sourceExpenseClaimId: null,
  sourceGatewaySettlementId: null, sourceReconciliationAdjustmentId: null,
  allocationLineId: '701', correctionGroupId: null, reversesEntryId: null, createdAt: '2026-08-19T10:00:00Z',
}
const POSTING = {
  id: '900', postingKey: 'CHARGE:31:ALLOCATION:70', sourceType: 'PROVIDER_CHARGE' as const, sourceId: '31',
  allocationDecisionId: '70', billingPeriodId: '55', status: 'POSTED', postedByMemberId: '3', postedAt: '2026-08-19T10:00:00Z',
  createdAt: '2026-08-19T10:00:00Z', visibleEntryCount: 1, visibleTotalAmount: '10.00000000', visibleCurrency: 'CNY',
  visibleTotals: { CNY: '10.00000000' }, entries: [ENTRY],
}

function renderPage(element: React.ReactElement, initialEntries = ['/ledger'], permissions = ['LEDGER_READ']) {
  mockedUseAuth.mockReturnValue({
    status: 'authenticated',
    user: { id: '1', email: 'admin@example.com', displayName: 'Admin', organizationId: '2', organizationMemberId: '3', permissions },
    login: vi.fn(), refreshMe: vi.fn(), logout: vi.fn(),
  } as ReturnType<typeof useAuth>)
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={queryClient}><MemoryRouter initialEntries={initialEntries}>{element}</MemoryRouter></QueryClientProvider>)
}

beforeEach(() => vi.clearAllMocks())

describe('LedgerListPage', () => {
  it('renders source, currency-aware totals, entry count and correction marker', async () => {
    mockedLedgerApi.listPostings.mockResolvedValue({ items: [POSTING, { ...POSTING, id: '905', sourceType: 'CORRECTION', postingKey: 'CORRECTION:5' }], page: 0, size: 50, totalElements: 2, totalPages: 1 })
    renderPage(<LedgerListPage />)
    await waitFor(() => expect(screen.getAllByText('纠正').length).toBeGreaterThan(0))
    expect(screen.getAllByText('10.00000000 CNY').length).toBeGreaterThan(0)
    expect(screen.getByText('供应商成本')).toBeInTheDocument()
  })
})

describe('Ledger detail pages', () => {
  it('posting detail exposes only server-visible entries and lineage links', async () => {
    mockedLedgerApi.getPosting.mockResolvedValue(POSTING)
    renderPage(<Routes><Route path="/ledger/postings/:id" element={<LedgerPostingDetailPage />} /></Routes>, ['/ledger/postings/900'])
    await waitFor(() => expect(screen.getByText('账本发布 #900')).toBeInTheDocument())
    expect(screen.getByText('查看')).toBeInTheDocument()
    expect(screen.getAllByText('10.00000000 CNY').length).toBeGreaterThan(0)
  })

  it('entry detail renders provider lineage and keeps stable IDs visible', async () => {
    mockedLedgerApi.getEntry.mockResolvedValue({ entry: ENTRY, posting: POSTING, lineage: {
      allocationLineId: '701', allocationDecisionId: '70', allocationDecisionStatus: 'CONFIRMED', chargeFactId: '31',
      chargeProviderCode: 'GLM', chargeReviewStatus: 'CLEAN', rawProviderRecordId: '41', importAttemptId: '42', importBatchId: '43',
      providerEvidenceId: '44', expenseClaimId: null, expenseStatus: null, expenseEvidenceId: null, correctionGroupId: null, reversesEntryId: null,
      correctedByCorrectionGroupId: null, correctionTargetEntryId: null,
    } })
    renderPage(<Routes><Route path="/ledger/entries/:id" element={<LedgerEntryDetailPage />} /></Routes>, ['/ledger/entries/901'])
    await waitFor(() => expect(screen.getByText('分录血缘 #901')).toBeInTheDocument())
    expect(screen.getByText('供应商成本')).toBeInTheDocument()
    expect(screen.getByText('GLM')).toBeInTheDocument()
    expect(screen.getByText('44')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '纠正' })).not.toBeInTheDocument()
  })

  it('hides correction for an entry already targeted by a correction group', async () => {
    mockedLedgerApi.getEntry.mockResolvedValue({ entry: ENTRY, posting: POSTING, lineage: {
      allocationLineId: '701', allocationDecisionId: '70', allocationDecisionStatus: 'CONFIRMED', chargeFactId: '31',
      chargeProviderCode: 'GLM', chargeReviewStatus: 'CLEAN', rawProviderRecordId: '41', importAttemptId: '42', importBatchId: '43',
      providerEvidenceId: '44', expenseClaimId: null, expenseStatus: null, expenseEvidenceId: null, correctionGroupId: null, reversesEntryId: null,
      correctedByCorrectionGroupId: '5', correctionTargetEntryId: null,
    } })
    renderPage(<Routes><Route path="/ledger/entries/:id" element={<LedgerEntryDetailPage />} /></Routes>, ['/ledger/entries/901'], ['LEDGER_READ', 'LEDGER_CORRECT'])
    await waitFor(() => expect(screen.getByText('分录血缘 #901')).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: '纠正' })).not.toBeInTheDocument()
  })

  it('allows a correction entry to be corrected again until it becomes a target', async () => {
    mockedLedgerApi.getEntry.mockResolvedValue({ entry: { ...ENTRY, id: '902', allocationLineId: null, correctionGroupId: '5' }, posting: POSTING, lineage: {
      allocationLineId: null, allocationDecisionId: null, allocationDecisionStatus: null, chargeFactId: '31',
      chargeProviderCode: 'GLM', chargeReviewStatus: 'CLEAN', rawProviderRecordId: '41', importAttemptId: '42', importBatchId: '43',
      providerEvidenceId: '44', expenseClaimId: null, expenseStatus: null, expenseEvidenceId: null, correctionGroupId: '5', reversesEntryId: null,
      correctedByCorrectionGroupId: null, correctionTargetEntryId: '901',
    } })
    renderPage(<Routes><Route path="/ledger/entries/:id" element={<LedgerEntryDetailPage />} /></Routes>, ['/ledger/entries/902'], ['LEDGER_READ', 'LEDGER_CORRECT'])
    await waitFor(() => expect(screen.getByText('分录血缘 #902')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: /纠\s*正/ })).toBeInTheDocument()
  })
})
