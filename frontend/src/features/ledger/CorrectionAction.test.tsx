import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { billingPeriodApi } from '../budgets/api/billingPeriodApi'
import { CorrectionAction } from './CorrectionAction'
import { ledgerApi, type LedgerEntryResponse } from './api/ledgerApi'

vi.mock('../budgets/api/billingPeriodApi', () => ({
  billingPeriodApi: { list: vi.fn() },
  billingPeriodKeys: { list: () => ['billing-periods'] },
}))
vi.mock('./api/ledgerApi', () => ({ ledgerApi: { correct: vi.fn() } }))

const mockedBillingPeriodApi = vi.mocked(billingPeriodApi)
const mockedLedgerApi = vi.mocked(ledgerApi)

const ENTRY: LedgerEntryResponse = {
  id: '901', postingId: '900', entryIndex: 0, entryType: 'COST', amount: '10.00000000', currency: 'CNY',
  targetType: 'PROJECT', targetId: '77', budgetId: '88', sourceChargeFactId: '31', sourceExpenseClaimId: null,
  allocationLineId: '701', correctionGroupId: null, reversesEntryId: null, createdAt: '2026-08-19T10:00:00Z',
}

function renderAction() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(<QueryClientProvider client={queryClient}><CorrectionAction entry={ENTRY} onCompleted={vi.fn()} /></QueryClientProvider>)
}

beforeEach(() => {
  vi.clearAllMocks()
  mockedBillingPeriodApi.list.mockResolvedValue([
    { id: '55', periodStart: '2026-08-01', periodEnd: '2026-09-01', status: 'OPEN', version: 0 },
    { id: '56', periodStart: '2026-09-01', periodEnd: '2026-10-01', status: 'CLOSED', version: 0 },
  ])
  mockedLedgerApi.correct.mockResolvedValue({ correctionGroupId: '5', posting: {} as never })
})

describe('CorrectionAction', () => {
  it('offers only OPEN periods and submits a reversal-only command with an idempotency key', async () => {
    renderAction()
    fireEvent.click(screen.getByRole('button', { name: /纠\s*正/ }))

    expect(screen.queryByText('56 · 2026-09-01 ~ 2026-10-01')).not.toBeInTheDocument()
    fireEvent.mouseDown(screen.getByText('选择 OPEN 账期'))
    await waitFor(() => expect(screen.getByText('55 · 2026-08-01 ~ 2026-09-01')).toBeInTheDocument())
    fireEvent.click(screen.getByText('55 · 2026-08-01 ~ 2026-09-01'))
    fireEvent.click(screen.getByRole('button', { name: '提交纠正' }))

    await waitFor(() => expect(mockedLedgerApi.correct).toHaveBeenCalledWith({
      targetEntryId: '901', correctionPeriodId: '55', mode: 'REVERSAL_ONLY', reasonCode: 'ALLOCATION_ERROR',
      reasonText: null, replacement: null,
    }, expect.any(String)))
  })

  it('sends replacement dimensions and displays a failed correction without retrying', async () => {
    mockedLedgerApi.correct.mockRejectedValue({
      isAxiosError: true,
      response: { data: { title: 'Period closed', status: 409, code: 'PERIOD_NOT_OPEN', detail: 'The period is closed.', traceId: null } },
    })
    renderAction()
    fireEvent.click(screen.getByRole('button', { name: /纠\s*正/ }))
    fireEvent.mouseDown(screen.getByText('选择 OPEN 账期'))
    await waitFor(() => expect(screen.getByText('55 · 2026-08-01 ~ 2026-09-01')).toBeInTheDocument())
    fireEvent.click(screen.getByText('55 · 2026-08-01 ~ 2026-09-01'))
    fireEvent.mouseDown(screen.getByText('仅反转'))
    fireEvent.click(screen.getByText('反转并替换'))
    fireEvent.change(screen.getByLabelText('金额'), { target: { value: '12.50000000' } })
    fireEvent.change(screen.getByLabelText('目标 ID'), { target: { value: '99' } })
    fireEvent.click(screen.getByRole('button', { name: '提交纠正' }))

    await waitFor(() => expect(mockedLedgerApi.correct).toHaveBeenCalledWith(expect.objectContaining({
      mode: 'REPLACE', replacement: {
        amount: '12.50000000', currency: 'CNY', projectId: '99', costCenterId: null, teamId: null,
      },
    }), expect.any(String)))
    await waitFor(() => expect(screen.getByText('当前账期未开放，暂不能执行此操作。')).toBeInTheDocument())
    expect(mockedLedgerApi.correct).toHaveBeenCalledTimes(1)
  })
})
