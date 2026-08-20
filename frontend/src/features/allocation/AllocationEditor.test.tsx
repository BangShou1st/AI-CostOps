import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { allocationApi, type AllocationDecision, type AllocationTargetRef } from './api/allocationApi'
import { AllocationEditor } from './AllocationEditor'

vi.mock('./api/allocationApi', () => ({
  allocationApi: {
    listTargets: vi.fn(),
    createManualDraft: vi.fn(),
    createManualDraftForExpense: vi.fn(),
    replaceLines: vi.fn(),
    confirm: vi.fn(),
    propose: vi.fn(),
  },
}))

const mockedAllocationApi = vi.mocked(allocationApi)

const DRAFT: AllocationDecision = {
  id: '21',
  subjectType: 'CHARGE_FACT',
  expenseClaimId: null,
      chargeFactId: '11',
  source: 'MANUAL',
  status: 'DRAFT',
  allocationRule: null,
  createdByMemberId: '3',
  createdAt: '2026-01-05T00:00:00Z',
  lines: [
  { id: '501', lineIndex: 0, allocatedAmount: '4.00000000', currency: 'CNY', projectId: '5', costCenterId: null, teamId: null },
  { id: '502', lineIndex: 1, allocatedAmount: '6.00000000', currency: 'CNY', projectId: null, costCenterId: '6', teamId: null },
  ],
}

const RULE_DRAFT: AllocationDecision = {
  ...DRAFT,
  id: '22',
  source: 'RULE',
  allocationRule: { id: '41', ruleKey: 'glm-key', version: 1, priority: 10 },
  lines: [
  { id: '503', lineIndex: 0, allocatedAmount: '10.00000000', currency: 'CNY', projectId: '5', costCenterId: null, teamId: null },
  ],
}

const TARGETS: AllocationTargetRef[] = [
  { type: 'PROJECT', id: '5', name: '平台' },
  { type: 'COST_CENTER', id: '6', name: '市场部' },
  { type: 'TEAM', id: '7', name: '平台组' },
]

function renderEditor(props: Partial<Parameters<typeof AllocationEditor>[0]> = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const onChanged = vi.fn()
  const onProposalApplied = vi.fn()
  render(
    <QueryClientProvider client={queryClient}>
      <AllocationEditor
        chargeId="11"
        subjectAmount="10.00000000"
        subjectCurrency="CNY"
        reviewStatus="CLEAN"
        draft={null}
        canEdit
        canConfirm
        onChanged={onChanged}
        onProposalApplied={onProposalApplied}
        {...props}
      />
    </QueryClientProvider>,
  )
  return { onChanged, onProposalApplied }
}

beforeEach(() => {
  vi.clearAllMocks()
  mockedAllocationApi.listTargets.mockResolvedValue(TARGETS)
})

describe('AllocationEditor', () => {
  it('shows source, allocated total, and remaining exactly', async () => {
    renderEditor({ draft: DRAFT })

    await waitFor(() => {
      expect(screen.getByText(/来源金额：10.00000000 CNY/)).toBeInTheDocument()
    })
    expect(screen.getByText(/已分配：10.00000000 CNY/)).toBeInTheDocument()
    expect(screen.getByText(/精确分配/)).toBeInTheDocument()
  })

  it('shows unallocated and over-allocated remainders exactly', async () => {
    renderEditor({ draft: DRAFT })
    const amountInputs = screen.getAllByLabelText(/第 \d 行金额/)
    fireEvent.change(amountInputs[0], { target: { value: '3.00000000' } })

    await waitFor(() => expect(screen.getByText(/未分配金额：1.00000000 CNY/)).toBeInTheDocument())

    fireEvent.change(amountInputs[0], { target: { value: '5.00000000' } })
    await waitFor(() => expect(screen.getByText(/超额分配：1.00000000 CNY/)).toBeInTheDocument())
  })

  it('disables confirm while remaining is not exact zero', async () => {
    renderEditor({ draft: DRAFT })
    const amountInputs = screen.getAllByLabelText(/第 \d 行金额/)
    fireEvent.change(amountInputs[0], { target: { value: '3.00000000' } })

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '确认分摊' })).toBeDisabled()
    })
  })

  it('exact confirm calls the real confirm contract and refreshes', async () => {
    mockedAllocationApi.confirm.mockResolvedValue({ ...DRAFT, status: 'CONFIRMED' })
    const { onChanged } = renderEditor({ draft: DRAFT })

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '确认分摊' })).toBeEnabled()
    })
    fireEvent.click(screen.getByRole('button', { name: '确认分摊' }))

    await waitFor(() => expect(mockedAllocationApi.confirm).toHaveBeenCalledTimes(1))
    expect(mockedAllocationApi.confirm.mock.calls[0][0]).toBe('21')
    expect(onChanged).toHaveBeenCalled()
  })

  it('blocks confirm for SUSPECTED_DUPLICATE charges', async () => {
    renderEditor({ draft: DRAFT, reviewStatus: 'SUSPECTED_DUPLICATE' })

    await waitFor(() => {
      expect(screen.getByText('疑似重复待处理')).toBeInTheDocument()
    })
    expect(screen.getByRole('button', { name: '确认分摊' })).toBeDisabled()
  })

  it('displays the backend problem detail on 409 conflict', async () => {
    mockedAllocationApi.confirm.mockRejectedValue({
      isAxiosError: true,
      response: {
        data: {
          title: 'Allocation sum mismatch',
          status: 409,
          detail: 'The lines must exactly sum to the charge amount.',
          code: 'ALLOCATION_SUM_MISMATCH',
          traceId: 't-1',
        },
      },
    })
    renderEditor({ draft: DRAFT })

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '确认分摊' })).toBeEnabled()
    })
    fireEvent.click(screen.getByRole('button', { name: '确认分摊' }))

    await waitFor(() => {
      expect(screen.getByText(/分摊金额不一致（ALLOCATION_SUM_MISMATCH）/)).toBeInTheDocument()
    })
    expect(screen.getByText('分摊明细合计与费用金额不一致，请检查后重试。')).toBeInTheDocument()
  })

  it('loads targets from the allocation-targets directory endpoint', async () => {
    renderEditor({ draft: DRAFT })

    await waitFor(() => expect(mockedAllocationApi.listTargets).toHaveBeenCalledTimes(1))
  })

  it('creates a manual draft through the real contract and refetches', async () => {
    mockedAllocationApi.createManualDraft.mockResolvedValue(DRAFT)
    const { onChanged } = renderEditor()

    fireEvent.click(screen.getByRole('button', { name: '添加分摊行' }))
    const amountInput = screen.getByLabelText('第 1 行金额')
    fireEvent.change(amountInput, { target: { value: '10.00000000' } })

    // targets load asynchronously; wait for the project option before selecting
    await screen.findByRole('option', { name: '平台' })
    fireEvent.change(screen.getByLabelText('第 1 行分摊对象'), { target: { value: 'PROJECT:5' } })

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '创建分摊草稿' })).toBeEnabled()
    })
    fireEvent.click(screen.getByRole('button', { name: '创建分摊草稿' }))

    await waitFor(() => expect(mockedAllocationApi.createManualDraft).toHaveBeenCalledTimes(1))
    const [chargeId, lines] = mockedAllocationApi.createManualDraft.mock.calls[0]
    expect(chargeId).toBe('11')
    expect(lines).toEqual([
      { allocatedAmount: '10.00000000', currency: 'CNY', projectId: '5', costCenterId: null, teamId: null },
    ])
    expect(onChanged).toHaveBeenCalled()
  })

  it('creates an expense manual draft from user-typed amounts and refreshes decisions', async () => {
    // Real UAT flow: APPROVED Expense 1, amount typed as 129.5 (short decimal),
    // target UAT Project (id 1). The short decimal must be normalized to
    // scale-8 before the request leaves the browser.
    mockedAllocationApi.listTargets.mockResolvedValue([{ type: 'PROJECT', id: '1', name: 'UAT Project' }])
    mockedAllocationApi.createManualDraftForExpense.mockResolvedValue({
      ...DRAFT,
      subjectType: 'EXPENSE_CLAIM',
      expenseClaimId: '1',
      chargeFactId: null,
    })
    const { onChanged } = renderEditor({ subjectType: 'EXPENSE_CLAIM', subjectId: '1', chargeId: '' })

    fireEvent.click(screen.getByRole('button', { name: '添加分摊行' }))
    fireEvent.change(screen.getByLabelText('第 1 行金额'), { target: { value: '129.5' } })

    await screen.findByRole('option', { name: 'UAT Project' })
    fireEvent.change(screen.getByLabelText('第 1 行分摊对象'), { target: { value: 'PROJECT:1' } })

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '创建分摊草稿' })).toBeEnabled()
    })
    fireEvent.click(screen.getByRole('button', { name: '创建分摊草稿' }))

    await waitFor(() => expect(mockedAllocationApi.createManualDraftForExpense).toHaveBeenCalledTimes(1))
    const [expenseId, lines, idempotencyKey] = mockedAllocationApi.createManualDraftForExpense.mock.calls[0]
    expect(expenseId).toBe('1')
    expect(lines).toEqual([
      { allocatedAmount: '129.50000000', currency: 'CNY', projectId: '1', costCenterId: null, teamId: null },
    ])
    expect(idempotencyKey).toBeTruthy()
    expect(onChanged).toHaveBeenCalled()
  })

  it('selecting a cost center keeps targetType in sync and submits costCenterId only', async () => {
    mockedAllocationApi.createManualDraft.mockResolvedValue(DRAFT)
    renderEditor()

    fireEvent.click(screen.getByRole('button', { name: '添加分摊行' }))
    fireEvent.change(screen.getByLabelText('第 1 行金额'), { target: { value: '10.00000000' } })

    await screen.findByRole('option', { name: '市场部' })
    fireEvent.change(screen.getByLabelText('第 1 行分摊对象'), { target: { value: 'COST_CENTER:6' } })

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '创建分摊草稿' })).toBeEnabled()
    })
    fireEvent.click(screen.getByRole('button', { name: '创建分摊草稿' }))

    await waitFor(() => expect(mockedAllocationApi.createManualDraft).toHaveBeenCalledTimes(1))
    const [, lines] = mockedAllocationApi.createManualDraft.mock.calls[0]
    expect(lines).toEqual([
      { allocatedAmount: '10.00000000', currency: 'CNY', projectId: null, costCenterId: '6', teamId: null },
    ])
  })

  it('selecting a team submits teamId only', async () => {
    mockedAllocationApi.createManualDraft.mockResolvedValue(DRAFT)
    renderEditor()

    fireEvent.click(screen.getByRole('button', { name: '添加分摊行' }))
    fireEvent.change(screen.getByLabelText('第 1 行金额'), { target: { value: '10.00000000' } })

    await screen.findByRole('option', { name: '平台组' })
    fireEvent.change(screen.getByLabelText('第 1 行分摊对象'), { target: { value: 'TEAM:7' } })

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '创建分摊草稿' })).toBeEnabled()
    })
    fireEvent.click(screen.getByRole('button', { name: '创建分摊草稿' }))

    await waitFor(() => expect(mockedAllocationApi.createManualDraft).toHaveBeenCalledTimes(1))
    const [, lines] = mockedAllocationApi.createManualDraft.mock.calls[0]
    expect(lines).toEqual([
      { allocatedAmount: '10.00000000', currency: 'CNY', projectId: null, costCenterId: null, teamId: '7' },
    ])
  })

  it('keeps an existing cost center line selected after rerender from directory data', async () => {
    renderEditor({ draft: DRAFT })

    await screen.findAllByRole('option', { name: '市场部' })
    const secondSelect = screen.getByLabelText('第 2 行分摊对象') as HTMLSelectElement
    expect(secondSelect.value).toBe('COST_CENTER:6')
  })

  it('replaces lines through the naturally idempotent PUT without an Idempotency-Key', async () => {
    mockedAllocationApi.replaceLines.mockResolvedValue(DRAFT)
    renderEditor({ draft: DRAFT })

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '保存分摊' })).toBeEnabled()
    })
    fireEvent.click(screen.getByRole('button', { name: '保存分摊' }))

    await waitFor(() => expect(mockedAllocationApi.replaceLines).toHaveBeenCalledTimes(1))
    // The PUT line replacement is naturally idempotent: the contract takes
    // only the decision id and the line set, never an idempotency key.
    expect(mockedAllocationApi.replaceLines.mock.calls[0].length).toBe(2)
    const [decisionId, lines] = mockedAllocationApi.replaceLines.mock.calls[0]
    expect(decisionId).toBe('21')
    expect(lines).toHaveLength(2)
  })

  it('renders a RULE draft read-only with confirm and manual-override actions', async () => {
    renderEditor({ draft: RULE_DRAFT })

    const amountInput = screen.getByLabelText('第 1 行金额') as HTMLInputElement
    expect(amountInput.disabled).toBe(true)
    const targetSelect = screen.getByLabelText('第 1 行分摊对象') as HTMLSelectElement
    expect(targetSelect.disabled).toBe(true)
    expect(screen.queryByRole('button', { name: '添加分摊行' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '保存分摊' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '按规则生成' })).not.toBeInTheDocument()
    expect(screen.getByText(/规则草案/)).toBeInTheDocument()
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '确认分摊' })).toBeEnabled()
    })
    expect(screen.getByRole('button', { name: '手动覆盖' })).toBeEnabled()
  })

  it('manual override of a RULE draft creates a MANUAL draft from its lines', async () => {
    mockedAllocationApi.createManualDraft.mockResolvedValue(DRAFT)
    renderEditor({ draft: RULE_DRAFT })

    fireEvent.click(screen.getByRole('button', { name: '手动覆盖' }))

    await waitFor(() => expect(mockedAllocationApi.createManualDraft).toHaveBeenCalledTimes(1))
    const [chargeId, lines] = mockedAllocationApi.createManualDraft.mock.calls[0]
    expect(chargeId).toBe('11')
    expect(lines).toEqual([
      { allocatedAmount: '10.00000000', currency: 'CNY', projectId: '5', costCenterId: null, teamId: null },
    ])
  })

  it('resyncs local lines when the parent refetches a changed draft', () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const element = (draft: AllocationDecision | null) => (
      <QueryClientProvider client={queryClient}>
        <AllocationEditor
          chargeId="11"
          subjectAmount="10.00000000"
          subjectCurrency="CNY"
          reviewStatus="CLEAN"
          draft={draft}
          canEdit
          canConfirm
          onChanged={vi.fn()}
          onProposalApplied={vi.fn()}
        />
      </QueryClientProvider>
    )
    const { rerender } = render(element(DRAFT))
    fireEvent.change(screen.getByLabelText('第 1 行金额'), { target: { value: '1.00000000' } })
    expect((screen.getByLabelText('第 1 行金额') as HTMLInputElement).value).toBe('1.00000000')

    // Backend truth after refetch: one line only, amount back to 4.
    rerender(element({ ...DRAFT, lines: [DRAFT.lines[0]] }))

    expect(screen.getAllByLabelText(/行金额/)).toHaveLength(1)
    expect((screen.getByLabelText('第 1 行金额') as HTMLInputElement).value).toBe('4.00000000')
  })

  it('hides creation and proposal actions once a decision is CONFIRMED', async () => {
    renderEditor({ draft: null, hasConfirmed: true })

    expect(screen.queryByRole('button', { name: '添加分摊行' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '创建分摊草稿' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '按规则生成' })).not.toBeInTheDocument()
  })

  it('adds lines only when the target is chosen', async () => {
    renderEditor()
    fireEvent.click(screen.getByRole('button', { name: '添加分摊行' }))
    const amountInput = screen.getByLabelText('第 1 行金额')
    fireEvent.change(amountInput, { target: { value: '10.00000000' } })

    // amount set but no target: save stays disabled (shape invalid)
    expect(screen.getByRole('button', { name: '创建分摊草稿' })).toBeDisabled()
  })
})
