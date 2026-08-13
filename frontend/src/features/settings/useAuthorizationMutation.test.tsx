import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { useState } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { toProblemDetail } from '../../api/problem'
import { useAuth } from '../auth/AuthSessionProvider'
import { useAuthorizationMutation } from './useAuthorizationMutation'

vi.mock('../auth/AuthSessionProvider', () => ({ useAuth: vi.fn() }))

const mockedUseAuth = vi.mocked(useAuth)

const forbiddenProblem = {
  isAxiosError: true,
  response: {
    status: 403,
    data: { title: 'Forbidden', status: 403, detail: 'You lack the required permission.', code: 'FORBIDDEN', traceId: 't8' },
  },
}

function Harness({ mutationFn }: { mutationFn: (value: string) => Promise<string> }) {
  const [error, setError] = useState<unknown>()
  const [calls, setCalls] = useState(0)
  const mutation = useAuthorizationMutation({
    mutationFn,
    onSuccess: () => setCalls((current) => current + 1),
    onError: (caught) => setError(caught),
  })
  return (
    <div>
      <button onClick={() => mutation.mutate('x')}>Run mutation</button>
      <span>{calls}</span>
      {error !== undefined && <p role="alert">{toProblemDetail(error).detail}</p>}
    </div>
  )
}

function renderHarness(mutationFn: (value: string) => Promise<string>) {
  const queryClient = new QueryClient({ defaultOptions: { mutations: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}><Harness mutationFn={mutationFn} /></QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  mockedUseAuth.mockReturnValue({
    status: 'authenticated',
    user: { id: '1', email: 'a@b.c', displayName: 'A', organizationId: '2', organizationMemberId: '3', permissions: [] },
    login: vi.fn(),
    logout: vi.fn(),
    refreshMe: vi.fn().mockResolvedValue({ id: '1', email: 'a@b.c', displayName: 'A', organizationId: '2', organizationMemberId: '3', permissions: [] }),
  } as ReturnType<typeof useAuth>)
})

describe('useAuthorizationMutation', () => {
  it('forbiddenMutationRefreshesMeExactlyOnce', async () => {
    const mutationFn = vi.fn().mockRejectedValue(forbiddenProblem)
    renderHarness(mutationFn)

    fireEvent.click(screen.getByRole('button', { name: 'Run mutation' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('You lack the required permission.')
    await waitFor(() => {
      expect(mockedUseAuth.mock.results[0].value.refreshMe).toHaveBeenCalledTimes(1)
    })
  })

  it('forbiddenMutationIsNotRetried', async () => {
    const mutationFn = vi.fn().mockRejectedValue(forbiddenProblem)
    renderHarness(mutationFn)

    fireEvent.click(screen.getByRole('button', { name: 'Run mutation' }))
    await screen.findByRole('alert')

    expect(mutationFn).toHaveBeenCalledTimes(1)
  })

  it('keeps refreshing once per forbidden mutation', async () => {
    const mutationFn = vi.fn().mockRejectedValue(forbiddenProblem)
    renderHarness(mutationFn)

    const button = screen.getByRole('button', { name: 'Run mutation' })
    fireEvent.click(button)
    await screen.findByRole('alert')
    fireEvent.click(button)
    await waitFor(() => {
      expect(mockedUseAuth.mock.results[0].value.refreshMe).toHaveBeenCalledTimes(2)
      expect(mutationFn).toHaveBeenCalledTimes(2)
    })
  })

  it('does not refresh me for a non-forbidden error', async () => {
    const mutationFn = vi.fn().mockRejectedValue({
      isAxiosError: true,
      response: { status: 500, data: { title: 'Server error', status: 500, detail: 'Something failed.', code: 'INTERNAL_ERROR', traceId: 't9' } },
    })
    renderHarness(mutationFn)

    fireEvent.click(screen.getByRole('button', { name: 'Run mutation' }))
    await screen.findByRole('alert')

    expect(mockedUseAuth.mock.results[0].value.refreshMe).not.toHaveBeenCalled()
  })
})
