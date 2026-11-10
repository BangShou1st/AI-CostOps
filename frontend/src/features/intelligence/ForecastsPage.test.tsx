import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { overviewFixture } from './benchmark/overviewFixture'
import { ForecastsPage } from './ForecastsPage'

const forecasts = [
  overviewFixture.forecast,
  { ...overviewFixture.forecast, scopeType: 'PROJECT', scopeKey: 'project:7', projectedAmount: '4210.55', method: 'RECENT_RUN_RATE', confidence: 'LOW', historyBucketCount: 8 },
]

function renderPreview() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/intelligence/forecasts']}>
        <ForecastsPage preview={{ currency: 'USD', forecasts }} />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('ForecastsPage', () => {
  it('presents derived evidence with method honesty', () => {
    renderPreview()
    expect(screen.getByRole('heading', { name: '预测', level: 1 })).toBeInTheDocument()
    expect(screen.getByText(/基于历史数据的自动预测/)).toBeInTheDocument()
    expect(screen.getAllByText(/DAMPED_HOLT/).length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText(/RECENT_RUN_RATE/).length).toBeGreaterThanOrEqual(1)
    expect(screen.getByText(/仅供参考/)).toBeInTheDocument()
    expect(screen.getByText(/历史观测不足 3 天时不生成预测/)).toBeInTheDocument()
    expect(screen.getAllByText(/连续有效观测/).length).toBeGreaterThan(1)
  })
  it('labels the remaining-period amount and uncalibrated sample grades honestly', () => {
    renderPreview()
    expect(screen.getByText('组织剩余期间预计费用')).toBeInTheDocument()
    expect(screen.getAllByText(/剩余期间预计费用/).length).toBeGreaterThanOrEqual(2)
    expect(screen.getByText(/未经准确率校准/)).toBeInTheDocument()
    expect(screen.getAllByText(/样本等级/).length).toBeGreaterThanOrEqual(1)
    expect(screen.queryByText(/置信度/)).not.toBeInTheDocument()
    expect(screen.getByText(/出现冲正或样本不足时/)).toBeInTheDocument()
  })
  it('renders an empty state without inventing precision', () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter><ForecastsPage preview={{ currency: 'USD', forecasts: [] }} /></MemoryRouter>
      </QueryClientProvider>,
    )
    expect(screen.getByText(/暂无预测/)).toBeInTheDocument()
  })
})
