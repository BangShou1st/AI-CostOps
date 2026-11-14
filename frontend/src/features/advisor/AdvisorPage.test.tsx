import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from '../auth/AuthSessionProvider'
import { AdvisorPage } from './AdvisorPage'
import { advisorApi } from './api/advisorApi'
import { advisorCompletedFixture, advisorProfileFixture, advisorRunningFixture } from './benchmark/advisorFixture'

vi.mock('../auth/AuthSessionProvider', () => ({ useAuth: vi.fn() }))
vi.mock('../../auth/authApi', () => ({ apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn() } }))
vi.mock('./api/advisorApi', () => ({
  advisorApi: { profile: vi.fn(), updateProfile: vi.fn(), requestExplanation: vi.fn(), explanation: vi.fn(), retry: vi.fn() },
}))

const mockedUseAuth = vi.mocked(useAuth)
const mockedAdvisorApi = vi.mocked(advisorApi)

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
  it('allows a manager to create the first profile when none exists', async () => {
    authAs(['AI_ADVISOR_MANAGE'])
    mockedAdvisorApi.profile.mockRejectedValue({ response: { status: 404 } })
    mockedAdvisorApi.updateProfile.mockResolvedValue(advisorProfileFixture)

    renderPage()

    expect(await screen.findByText('暂无执行配置，请创建首个配置。')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '更新执行配置' }))

    expect(screen.getByLabelText('执行模型 ID')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('执行模型 ID'), { target: { value: '77' } })
    fireEvent.change(screen.getByLabelText('项目 ID'), { target: { value: '7' } })
    fireEvent.change(screen.getByLabelText('财务范围 ID'), { target: { value: '7' } })

    const save = screen.getByRole('button', { name: '保存新版本' })
    await waitFor(() => expect(save).toBeEnabled())
    fireEvent.click(save)

    await waitFor(() => expect(mockedAdvisorApi.updateProfile).toHaveBeenCalledWith({
      providerModelId: 77,
      projectId: 7,
      financialScopeType: 'PROJECT',
      financialScopeId: 7,
      budgetEnforcementMode: 'REQUIRED',
    }))
  })
})
