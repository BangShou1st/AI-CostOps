import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { billingPeriodApi } from '../budgets/api/billingPeriodApi'
import { budgetApi } from '../budgets/api/budgetApi'
import { commitmentApi } from '../budgets/api/commitmentApi'
import { CommitmentLinkPicker } from './CommitmentLinkPicker'

vi.mock('../budgets/api/billingPeriodApi', () => ({
  billingPeriodApi: { list: vi.fn() },
  billingPeriodKeys: { list: () => ['billing-period', 'list'] },
}))
vi.mock('../budgets/api/budgetApi', () => ({
  budgetApi: { list: vi.fn() },
  budgetKeys: { list: (params: unknown) => ['budget', 'list', params] },
}))
vi.mock('../budgets/api/commitmentApi', () => ({
  commitmentApi: { list: vi.fn() },
  commitmentKeys: { byBudget: (budgetId: string) => ['commitment', 'list', { budgetId }] },
}))

const mockedPeriods = vi.mocked(billingPeriodApi)
const mockedBudgets = vi.mocked(budgetApi)
const mockedCommitments = vi.mocked(commitmentApi)

function renderPicker(onChange: (links: { allocationLineId: string; commitmentId: string }[]) => void) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <CommitmentLinkPicker
        effectiveAt="2026-08-19"
        value={[]}
        onChange={onChange}
        lines={[
          { id: '701', lineIndex: 0, allocatedAmount: '10.00000000', currency: 'CNY', projectId: '5', costCenterId: null, teamId: null },
          { id: '702', lineIndex: 1, allocatedAmount: '8.00000000', currency: 'CNY', projectId: '6', costCenterId: null, teamId: null },
          { id: '703', lineIndex: 2, allocatedAmount: '-1.00000000', currency: 'CNY', projectId: '5', costCenterId: null, teamId: null },
        ]}
      />
    </QueryClientProvider>,
  )
}

describe('CommitmentLinkPicker', () => {
  it('offers one optional selector per positive line with exact target to ORG fallback', async () => {
    mockedPeriods.list.mockResolvedValue([
      { id: '55', periodStart: '2026-08-01', periodEnd: '2026-09-01', status: 'OPEN', version: 0 },
    ])
    mockedBudgets.list.mockResolvedValue({
      items: [
        { id: '801', billingPeriodId: '55', scopeType: 'PROJECT', scopeId: '5', currency: 'CNY', totalAmount: '100', actualAmount: '0', committedAmount: '10', availableAmount: '90', overBudget: false, status: 'ACTIVE', version: 0, createdAt: '', updatedAt: '' },
        { id: '802', billingPeriodId: '55', scopeType: 'ORG', scopeId: '1', currency: 'CNY', totalAmount: '100', actualAmount: '0', committedAmount: '8', availableAmount: '92', overBudget: false, status: 'ACTIVE', version: 0, createdAt: '', updatedAt: '' },
      ], page: 0, size: 200, totalElements: 2, totalPages: 1,
    })
    mockedCommitments.list.mockImplementation(async ({ budgetId }) => ({
      items: [{ id: budgetId === '801' ? '901' : '902', budgetId: budgetId ?? '', status: 'ACTIVE', requestedAmount: '10', approvedAmount: '10', remainingAmount: '10', version: 1, createdAt: '', updatedAt: '', approvalCaseId: null, approvalStatus: null, history: [] }],
      page: 0, size: 100, totalElements: 1, totalPages: 1,
    }))
    const onChange = vi.fn()
    renderPicker(onChange)

    await waitFor(() => expect(screen.getAllByRole('combobox')).toHaveLength(2))
    expect(mockedCommitments.list).toHaveBeenCalledTimes(2)

    fireEvent.mouseDown(screen.getAllByRole('combobox')[1])
    await waitFor(() => expect(screen.getByText('#902 · 剩余 10')).toBeInTheDocument())
    fireEvent.click(screen.getByText('#902 · 剩余 10'))
    expect(onChange).toHaveBeenCalledWith([{ allocationLineId: '702', commitmentId: '902' }])
  })
})
