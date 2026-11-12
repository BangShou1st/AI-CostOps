import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from '../auth/AuthSessionProvider'
import { billingPeriodApi, billingPeriodKeys } from '../budgets/api/billingPeriodApi'
import { periodCloseApi } from './api/periodCloseApi'
import { PeriodClosePage } from './PeriodClosePage'

vi.mock('../auth/AuthSessionProvider', () => ({ useAuth: vi.fn() }))
vi.mock('./api/periodCloseApi', () => ({
  periodCloseApi: { getReadiness: vi.fn(), listCloseRuns: vi.fn(), close: vi.fn(), reopen: vi.fn() },
}))
vi.mock('../budgets/api/billingPeriodApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../budgets/api/billingPeriodApi')>()
  return { ...actual, billingPeriodApi: { list: vi.fn() } }
})
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>()
  return { ...actual, useNavigate: () => vi.fn(), useParams: () => ({ periodId: '10' }) }
})

const mockedUseAuth = vi.mocked(useAuth)
const mockedPeriodCloseApi = vi.mocked(periodCloseApi)
const mockedBillingPeriodApi = vi.mocked(billingPeriodApi)

const period = {
  id: '10', periodStart: '2026-08-01', periodEnd: '2026-09-01', status: 'OPEN' as const, version: 0,
}
const blockerCodes = [
  'OPEN_IMPORTS', 'UNRESOLVED_DUPLICATES', 'UNALLOCATED_CHARGES', 'UNPOSTED_APPROVED_EXPENSES',
  'OPEN_MATERIAL_RECONCILIATION', 'PENDING_CORRECTIONS', 'LEDGER_INTEGRITY',
] as const
const readiness = {
  billingPeriodId: '10', periodStatus: 'OPEN' as const, closeGeneration: '0', ready: true, preview: true as const,
  checks: blockerCodes.map((blockerCode) => ({ blockerCode, result: 'PASS' as const, itemCount: 0, summary: {}, evaluatedAt: '2026-08-21T01:00:00Z' })),
}
const closeRun = {
  billingPeriodId: '10', periodStatus: 'OPEN' as const, closeGeneration: '0', closingStartedAt: null, closedAt: null,
  reopenedAt: null, runId: '20', runStatus: 'BLOCKED' as const, attemptNo: 1, reconciliationRunId: null,
  startedByMemberId: '3', startedAt: '2026-08-21T01:00:00Z', finishedAt: '2026-08-21T01:00:01Z',
  errorCode: null, errorSummary: null, checks: readiness.checks,
}

function renderPage(permissions: string[]) {
  mockedUseAuth.mockReturnValue({
    status: 'authenticated',
    user: { id: '1', email: 'finance@example.com', displayName: 'Finance', organizationId: '2', organizationMemberId: '3', permissions },
    login: vi.fn(), refreshMe: vi.fn(), logout: vi.fn(),
  } as ReturnType<typeof useAuth>)
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const result = render(
    <QueryClientProvider client={queryClient}>
      <PeriodClosePage />
    </QueryClientProvider>,
  )
  return { queryClient, ...result }
}

beforeEach(() => {
  vi.clearAllMocks()
  mockedBillingPeriodApi.list.mockResolvedValue([period])
  mockedPeriodCloseApi.getReadiness.mockResolvedValue(readiness)
  mockedPeriodCloseApi.listCloseRuns.mockResolvedValue({ items: [closeRun], page: 0, size: 20, totalElements: 21, totalPages: 2 })
})

afterEach(() => cleanup())

describe('PeriodClosePage', () => {
  it('renders all seven blocker labels in Chinese and paginates close history from the backend', async () => {
    renderPage(['PERIOD_READ'])

    expect(await screen.findByText('未完成的导入任务')).toBeInTheDocument()
    expect(screen.getByText('未解决的重复记录')).toBeInTheDocument()
    expect(screen.getByText('未完成分摊的费用')).toBeInTheDocument()
    expect(screen.getByText('已批准但未入账的报销')).toBeInTheDocument()
    expect(screen.getByText('未解决的重要对账差异')).toBeInTheDocument()
    expect(screen.getByText('待处理的账务更正')).toBeInTheDocument()
    expect(screen.getByText('账本一致性')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '关闭账期' })).not.toBeInTheDocument()

    fireEvent.click(screen.getByTitle('2'))
    await waitFor(() => {
      expect(mockedPeriodCloseApi.listCloseRuns).toHaveBeenLastCalledWith('10', { page: 1, size: 20 })
    })
  })

  it('shows close action only with the close permission', async () => {
    renderPage(['PERIOD_READ', 'PERIOD_CLOSE'])

    expect(await screen.findByRole('button', { name: '关闭账期' })).toBeInTheDocument()
  })

  it('reads the billing-period list through the shared client and cache key', async () => {
    const { queryClient } = renderPage(['PERIOD_READ'])

    await waitFor(() => expect(mockedBillingPeriodApi.list).toHaveBeenCalledTimes(1))
    expect(queryClient.getQueryData(billingPeriodKeys.list())).toEqual([period])
  })

  it('invalidates the shared billing-period key after a close succeeds', async () => {
    mockedPeriodCloseApi.close.mockResolvedValue({ ...closeRun, periodStatus: 'CLOSED', runStatus: 'CLOSED' })
    renderPage(['PERIOD_READ', 'PERIOD_CLOSE'])

    fireEvent.click(await screen.findByRole('button', { name: '关闭账期' }))
    fireEvent.click(await screen.findByRole('button', { name: '确认关闭' }))

    await waitFor(() => expect(mockedPeriodCloseApi.close).toHaveBeenCalledTimes(1))
    // The active shared list query is invalidated and refetched (initial + refetch).
    await waitFor(() => expect(mockedBillingPeriodApi.list).toHaveBeenCalledTimes(2))
  })
})
