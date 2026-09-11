import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from '../auth/AuthSessionProvider'
import { AdvisorPage } from './AdvisorPage'
import { advisorCompletedFixture, advisorProfileFixture, advisorRunningFixture } from './benchmark/advisorFixture'

vi.mock('../auth/AuthSessionProvider', () => ({ useAuth: vi.fn() }))
vi.mock('../../auth/authApi', () => ({ apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn() } }))
vi.mock('./api/advisorApi', () => ({
  advisorApi: { profile: vi.fn(), updateProfile: vi.fn(), requestExplanation: vi.fn(), explanation: vi.fn(), retry: vi.fn() },
}))

const mockedUseAuth = vi.mocked(useAuth)

function authAs(permissions: string[]) {
  mockedUseAuth.mockReturnValue({
    status: 'authenticated',
    user: { id: '1', email: 'a@e.com', displayName: 'A', organizationId: '1', organizationMemberId: '3', permissions },
    login: vi.fn(), refreshMe: vi.fn(), logout: vi.fn(),
  } as ReturnType<typeof useAuth>)
}

function renderPage(preview?: { profile: typeof advisorProfileFixture; job: typeof advisorCompletedFixture }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/advisor']}>
        <AdvisorPage preview={preview} />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => vi.clearAllMocks())

describe('AdvisorPage', () => {
  it('separates verified facts from AI narrative without chat UI', () => {
    authAs(['AI_ADVISOR_USE'])
    renderPage({ profile: advisorProfileFixture, job: advisorCompletedFixture })
    expect(screen.getByRole('heading', { name: 'AI Advisor', level: 1 })).toBeInTheDocument()
    expect(screen.getByText('Verified financial facts')).toBeInTheDocument()
    expect(screen.getByText('AI-generated explanation')).toBeInTheDocument()
    expect(screen.getByText(/AI 未计算任何金额/)).toBeInTheDocument()
    expect(screen.getByText(/9 月 6 日/)).toBeInTheDocument()
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  })
  it('shows a restrained stepper for running jobs', () => {
    authAs(['AI_ADVISOR_USE'])
    renderPage({ profile: advisorProfileFixture, job: advisorRunningFixture })
    expect(screen.getByLabelText('执行状态')).toBeInTheDocument()
    expect(screen.getByText(/任务RUNNING中/)).toBeInTheDocument()
    expect(screen.queryByText(/typing|正在输入/)).not.toBeInTheDocument()
  })
})
