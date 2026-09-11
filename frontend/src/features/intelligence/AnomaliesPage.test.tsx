import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { overviewFixture } from './benchmark/overviewFixture'
import { AnomaliesPage } from './AnomaliesPage'

function renderPreview(anomalies = overviewFixture.anomalies) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/intelligence/anomalies']}>
        <AnomaliesPage preview={{ currency: 'USD', anomalies, summary: overviewFixture.summary }} />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => vi.clearAllMocks())

describe('AnomaliesPage', () => {
  it('renders deterministic detection framing, not AI wording', () => {
    renderPreview()
    expect(screen.getByRole('heading', { name: '异常', level: 1 })).toBeInTheDocument()
    expect(screen.getByText(/median／MAD／robust-z/)).toBeInTheDocument()
    expect(screen.getByText(/不是 AI 异常检测/)).toBeInTheDocument()
    expect(screen.getAllByText(/LOGICAL_MODEL/).length).toBeGreaterThanOrEqual(1)
  })
  it('opens an evidence drawer with drivers and methodology', async () => {
    renderPreview()
    const buttons = screen.getAllByRole('button', { name: /查看证据/ })
    fireEvent.click(buttons[0])
    expect(await screen.findByText('异常证据')).toBeInTheDocument()
    expect(screen.getByText(/project:atlas/)).toBeInTheDocument()
    expect(screen.getByText(/检测规则/)).toBeInTheDocument()
  })
  it('degrades malformed drivers gracefully', async () => {
    renderPreview([{ ...overviewFixture.anomalies[0], driversJson: '{broken' }])
    fireEvent.click(screen.getAllByRole('button', { name: /查看证据/ })[0])
    expect(await screen.findByText(/驱动证据暂时不可读/)).toBeInTheDocument()
  })
  it('renders an empty state when no anomalies exist', () => {
    renderPreview([])
    expect(screen.getByText(/暂无异常/)).toBeInTheDocument()
  })
})
