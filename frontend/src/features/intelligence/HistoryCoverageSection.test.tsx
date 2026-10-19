import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { coverageApi } from './api/coverageApi'
import { HistoryCoverageSection } from './HistoryCoverageSection'

const auth = vi.hoisted(() => ({ permissions: ['COST_READ', 'COST_COVERAGE_MANAGE'] }))
vi.mock('../auth/AuthSessionProvider', () => ({ useAuth: () => ({ user: { organizationId: '7', permissions: auth.permissions } }) }))
vi.mock('./api/coverageApi', () => ({ coverageApi: { scopes: vi.fn(), coverage: vi.fn(), review: vi.fn(), analyze: vi.fn() } }))
const report = {
  revision: 8, currency: 'CNY', scope: 'ORGANIZATION' as const, scopeId: 7, dateBasis: 'UTC_POSTING_DAY' as const,
  days: [{ postingDay: '2026-10-10', observation: 'UNKNOWN' as const, amount: null, reviewValid: false, review: null }],
}
function mount() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(<QueryClientProvider client={client}><HistoryCoverageSection currency="CNY" /></QueryClientProvider>)
}
async function openReview() {
  fireEvent.click(screen.getByRole('button', { name: '展开复核' }))
  fireEvent.click(await screen.findByRole('button', { name: '复核 2026-10-10' }))
}
beforeEach(() => {
  vi.clearAllMocks()
  auth.permissions = ['COST_READ', 'COST_COVERAGE_MANAGE']
  vi.mocked(coverageApi.scopes).mockResolvedValue([{ scope: 'PROJECT', scopeId: '9', name: 'Coverage Project' }])
  vi.mocked(coverageApi.coverage).mockResolvedValue(report)
  vi.mocked(coverageApi.review).mockResolvedValue({ revision: 9 })
  vi.mocked(coverageApi.analyze).mockResolvedValue({ runId: 1, status: 'COMPLETED' })
})

describe('HistoryCoverageSection', () => {
  it('does not read private coverage without cost permission', () => {
    auth.permissions = []
    mount()
    expect(screen.queryByText('历史覆盖复核')).not.toBeInTheDocument()
    expect(coverageApi.coverage).not.toHaveBeenCalled()
  })

  it('keeps unknown amounts unknown and makes a reader view read-only', async () => {
    auth.permissions = ['COST_READ']
    mount()
    fireEvent.click(screen.getByRole('button', { name: '展开复核' }))
    expect(await screen.findByText('尚未复核')).toBeInTheDocument()
    expect(screen.getAllByText('未知')).toHaveLength(2)
    expect(screen.queryByRole('button', { name: '复核 2026-10-10' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '重新分析' })).not.toBeInTheDocument()
  })

  it('requires a basis note and explicit completeness confirmation before saving', async () => {
    mount()
    await openReview()
    expect(screen.getByRole('button', { name: '保存复核' })).toBeDisabled()
    fireEvent.change(screen.getByRole('textbox', { name: '复核依据' }), { target: { value: 'All sources reconciled for the posting day' } })
    expect(screen.getByRole('button', { name: '保存复核' })).toBeDisabled()
    fireEvent.click(screen.getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '保存复核' }))
    await waitFor(() => expect(coverageApi.review).toHaveBeenCalledWith({ currency: 'CNY', scope: 'ORGANIZATION', scopeId: '7',
      postingDay: '2026-10-10', status: 'REVIEWED_COMPLETE', reviewNote: 'All sources reconciled for the posting day', expectedRevision: 8 }))
    expect(await screen.findByText('复核已保存。点击“重新分析”更新当前预测。')).toBeInTheDocument()
    expect(coverageApi.analyze).not.toHaveBeenCalled()
  })

  it('preserves a rejected review note and requires confirmation again after a conflict', async () => {
    vi.mocked(coverageApi.review).mockRejectedValue(Object.assign(new Error('conflict'), { isAxiosError: true,
      response: { status: 409, data: { title: 'Coverage changed', status: 409, code: 'STATE_CONFLICT', traceId: null,
        detail: 'Refresh coverage before recording another review.' } } }))
    mount()
    await openReview()
    fireEvent.change(screen.getByRole('textbox', { name: '复核依据' }), { target: { value: 'Keep this review note' } })
    fireEvent.click(screen.getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '保存复核' }))
    expect(await screen.findByText('覆盖已变更，请核对最新状态后重新确认。')).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: '复核依据' })).toHaveValue('Keep this review note')
    expect(screen.getByRole('checkbox')).not.toBeChecked()
    expect(screen.getByRole('button', { name: '保存复核' })).toBeDisabled()
  })

  it('records revocation as a separate action with its reason', async () => {
    vi.mocked(coverageApi.coverage).mockResolvedValue({ ...report, days: [{ ...report.days[0],
      observation: 'REVIEWED_ZERO', amount: '0', reviewValid: true,
      review: { status: 'REVIEWED_COMPLETE', basis: 'MANUAL_LEDGER_REVIEW', reviewNote: 'earlier review', updatedAt: '2026-10-11T00:00:00Z' } }] })
    mount()
    fireEvent.click(screen.getByRole('button', { name: '展开复核' }))
    fireEvent.click(await screen.findByRole('button', { name: '撤销 2026-10-10' }))
    fireEvent.change(screen.getByRole('textbox', { name: '撤销原因' }), { target: { value: 'Source coverage is incomplete' } })
    fireEvent.click(screen.getByRole('button', { name: '保存撤销' }))
    await waitFor(() => expect(coverageApi.review).toHaveBeenCalledWith(expect.objectContaining({ status: 'REVOKED', reviewNote: 'Source coverage is incomplete', expectedRevision: 8 })))
  })

  it('does not present a failed analysis as a success', async () => {
    vi.mocked(coverageApi.analyze).mockResolvedValue({ runId: 1, status: 'FAILED' })
    mount()
    fireEvent.click(screen.getByRole('button', { name: '展开复核' }))
    fireEvent.click(screen.getByRole('button', { name: '重新分析' }))
    expect(await screen.findByText('分析未完成，状态：FAILED。')).toBeInTheDocument()
    expect(coverageApi.analyze).toHaveBeenCalledWith('CNY')
    expect(screen.queryByText('分析已完成。有效历史不足时，预测列表仍为空。')).not.toBeInTheDocument()
  })
})
