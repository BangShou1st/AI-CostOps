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
    expect(screen.getByText(/衍生证据，不是账本真相/)).toBeInTheDocument()
    expect(screen.getAllByText(/DAMPED_HOLT/).length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText(/RECENT_RUN_RATE/).length).toBeGreaterThanOrEqual(1)
    expect(screen.getByText(/不是 AI 预测/)).toBeInTheDocument()
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
