import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from '../auth/AuthSessionProvider'
import { overviewFixture } from './benchmark/overviewFixture'
import { OverviewPage, type OverviewPreviewData } from './OverviewPage'

vi.mock('../auth/AuthSessionProvider', () => ({ useAuth: vi.fn() }))

const mockedUseAuth = vi.mocked(useAuth)

const preview: OverviewPreviewData = {
  currency: overviewFixture.currency,
  generatedAt: overviewFixture.generatedAt,
  summary: overviewFixture.summary,
  anomalies: overviewFixture.anomalies,
  forecasts: [overviewFixture.forecast],
  budgetRisk: overviewFixture.budgetRisk,
  recommendations: overviewFixture.recommendations,
}

function renderPreview() {
  mockedUseAuth.mockReturnValue({
    status: 'authenticated',
    user: {
      id: '1', email: 'a@example.com', displayName: 'A', organizationId: '1',
      organizationMemberId: '3', permissions: ['COST_READ', 'BUDGET_READ', 'AI_ADVISOR_USE'],
    },
    login: vi.fn(), refreshMe: vi.fn(), logout: vi.fn(),
  } as ReturnType<typeof useAuth>)
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/intelligence/overview']}>
        <OverviewPage preview={preview} authOverride={{ organizationId: '1', canReadBudget: true }} />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => vi.clearAllMocks())

describe('OverviewPage', () => {
  it('renders the four-question briefing spine instead of a KPI card wall', () => {
    renderPreview()
    expect(screen.getByRole('heading', { name: '成本智能总览', level: 1 })).toBeInTheDocument()
    expect(screen.getByText('Are we okay?')).toBeInTheDocument()
    expect(screen.getByText('What changed?')).toBeInTheDocument()
    expect(screen.getByText('What will happen?')).toBeInTheDocument()
    expect(screen.getByText('What can I do?')).toBeInTheDocument()
  })
  it('presents deterministic evidence with honest money formatting', () => {
    renderPreview()
    expect(screen.getAllByText('$12,430.22').length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText(/DAMPED_HOLT/).length).toBeGreaterThanOrEqual(1)
    expect(screen.getByText('观察')).toBeInTheDocument()
    expect(screen.getByText(/节约 \$1,319\.60/)).toBeInTheDocument()
    expect(screen.getAllByText(/需路由变更/).length).toBeGreaterThanOrEqual(1)
  })
  it('labels the advisory boundary instead of chat', () => {
    renderPreview()
    expect(screen.getByText('从证据到解释')).toBeInTheDocument()
    expect(screen.getByText('用 AI Advisor 解释本页')).toBeInTheDocument()
    expect(screen.queryByPlaceholderText(/输入/)).not.toBeInTheDocument()
  })
})


it('renders top drivers and the evidence-to-explanation band', () => {
  renderPreview()
  expect(screen.getByText('变化归因 · Top drivers')).toBeInTheDocument()
  expect(screen.getAllByText(/project:atlas/).length).toBeGreaterThanOrEqual(1)
  expect(screen.getByText('从证据到解释')).toBeInTheDocument()
  expect(screen.getByText('Verified financial facts')).toBeInTheDocument()
  expect(screen.getByText('AI-generated explanation')).toBeInTheDocument()
})
