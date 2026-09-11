import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from '../auth/AuthSessionProvider'
import { advisorApi } from './api/advisorApi'
import { AdvisorPage } from './AdvisorPage'
import { advisorFailedFixture, advisorProfileFixture } from './benchmark/advisorFixture'

vi.mock('../auth/AuthSessionProvider', () => ({ useAuth: vi.fn() }))
vi.mock('./api/advisorApi', () => ({
  advisorApi: { profile: vi.fn(), updateProfile: vi.fn(), requestExplanation: vi.fn(), explanation: vi.fn(), retry: vi.fn() },
}))

const mockedUseAuth = vi.mocked(useAuth)
const mockedAdvisor = vi.mocked(advisorApi)

beforeEach(() => vi.clearAllMocks())

describe('Advisor retry', () => {
  it('retries failed jobs through the real endpoint with append-only lineage', async () => {
    mockedUseAuth.mockReturnValue({
      status: 'authenticated',
      user: { id: '1', email: 'a@e.com', displayName: 'A', organizationId: '1', organizationMemberId: '3', permissions: ['AI_ADVISOR_USE'] },
      login: vi.fn(), refreshMe: vi.fn(), logout: vi.fn(),
    } as ReturnType<typeof useAuth>)
    mockedAdvisor.profile.mockResolvedValue(advisorProfileFixture)
    mockedAdvisor.requestExplanation.mockResolvedValue(advisorFailedFixture)
    mockedAdvisor.explanation.mockResolvedValue(advisorFailedFixture)
    mockedAdvisor.retry.mockResolvedValue({ ...advisorFailedFixture, attemptCount: 3 })
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter><AdvisorPage /></MemoryRouter>
      </QueryClientProvider>,
    )
    fireEvent.change(screen.getByLabelText('解释对象 ID'), { target: { value: 3 } })
    fireEvent.click(screen.getByRole('button', { name: '请求解释' }))
    expect(await screen.findByText('执行与叙事')).toBeInTheDocument()
    expect(screen.getAllByText(/append-only/).length).toBeGreaterThanOrEqual(1)
    fireEvent.click(screen.getByRole('button', { name: '重试' }))
    await waitFor(() => expect(mockedAdvisor.retry).toHaveBeenCalledWith(103))
  })
})
