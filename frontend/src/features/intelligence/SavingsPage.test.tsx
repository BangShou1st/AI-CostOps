import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from '../auth/authApi'
import { useAuth } from '../auth/AuthSessionProvider'
import { intelligenceApi } from './api/intelligenceApi'
import { overviewFixture } from './benchmark/overviewFixture'
import { SavingsPage } from './SavingsPage'

vi.mock('../auth/AuthSessionProvider', () => ({ useAuth: vi.fn() }))
vi.mock('../auth/authApi', () => ({ apiClient: { post: vi.fn() } }))
vi.mock('./api/intelligenceApi', () => ({ intelligenceApi: { recommendations: vi.fn() } }))

const mockedUseAuth = vi.mocked(useAuth)
const mockedPost = vi.mocked(apiClient.post)
const mockedList = vi.mocked(intelligenceApi.recommendations)

function authAs(permissions: string[]) {
  mockedUseAuth.mockReturnValue({
    status: 'authenticated',
    user: { id: '1', email: 'a@e.com', displayName: 'A', organizationId: '1', organizationMemberId: '3', permissions },
    login: vi.fn(), refreshMe: vi.fn(), logout: vi.fn(),
  } as ReturnType<typeof useAuth>)
}

function renderReal() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/intelligence/savings']}>
        <SavingsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function renderPreview() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/intelligence/savings']}>
        <SavingsPage preview={{ currency: 'USD', recommendations: overviewFixture.recommendations }} />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => vi.clearAllMocks())

describe('SavingsPage', () => {
  it('renders governed lifecycle wording without auto-routing CTAs', () => {
    authAs(['COST_READ', 'BUDGET_MANAGE'])
    renderPreview()
    expect(screen.getByRole('heading', { name: '节约建议', level: 1 })).toBeInTheDocument()
    expect(screen.getByText(/不会自动切换路由/)).toBeInTheDocument()
    expect(screen.queryByText(/Switch now|自动优化/)).not.toBeInTheDocument()
    expect(screen.getByText(/需路由变更/)).toBeInTheDocument()
  })
  it('hides mutations in preview mode and explains why', async () => {
    authAs(['COST_READ', 'BUDGET_MANAGE'])
    renderPreview()
    fireEvent.click(screen.getAllByRole('button', { name: /复核建议/ })[0])
    expect(await screen.findByText('复核节约建议')).toBeInTheDocument()
    expect(screen.getByText(/预览模式/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '确认' })).not.toBeInTheDocument()
  })
  it('performs acknowledge through the real endpoint', async () => {
    authAs(['COST_READ', 'BUDGET_MANAGE'])
    mockedList.mockResolvedValue(overviewFixture.recommendations)
    mockedPost.mockResolvedValue({ data: { ...overviewFixture.recommendations[0], status: 'ACKNOWLEDGED' } })
    renderReal()
    fireEvent.click((await screen.findAllByRole('button', { name: /复核建议/ }))[0])
    expect(await screen.findByText('复核节约建议')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '确认' }))
    await waitFor(() => expect(mockedPost).toHaveBeenCalledWith('/cost-intelligence/recommendations/3/acknowledge'))
  })
  it('tells viewers without govern permission that actions need an admin', async () => {
    authAs(['COST_READ'])
    mockedList.mockResolvedValue(overviewFixture.recommendations)
    renderReal()
    expect(await screen.findByText(/缺少 BUDGET_MANAGE 权限/)).toBeInTheDocument()
    fireEvent.click((await screen.findAllByRole('button', { name: /复核建议/ }))[0])
    expect(await screen.findByText('复核节约建议')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '确认' })).not.toBeInTheDocument()
  })
})
