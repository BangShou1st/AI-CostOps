import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { StrictMode } from 'react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ProtectedRoute } from '../../app/router/ProtectedRoute'
import { accessTokenStore } from './accessTokenStore'
import { authEvents } from './authEvents'
import { AuthSessionProvider, useAuth } from './AuthSessionProvider'

vi.mock('./authApi', () => ({
  authApi: {
    refresh: vi.fn(),
    me: vi.fn(),
    login: vi.fn(),
    logout: vi.fn(),
    register: vi.fn(),
    acceptInvitation: vi.fn(),
    forgotPassword: vi.fn(),
    resetPassword: vi.fn(),
  },
}))
vi.mock('antd', async (importOriginal) => {
  const actual = await importOriginal<typeof import('antd')>()
  return { ...actual, message: { warning: vi.fn(), error: vi.fn(), info: vi.fn(), success: vi.fn() } }
})

import { message } from 'antd'
import { authApi } from './authApi'

const mockedAuthApi = vi.mocked(authApi)

const user = {
  id: '1', email: 'admin@example.com', displayName: 'Admin',
  organizationId: '2', organizationMemberId: '11', permissions: ['USER_READ'],
}

function AuthProbe() {
  const auth = useAuth()
  if (auth.status === 'loading') return <div role="status">Restoring session</div>
  return (
    <MemoryRouter initialEntries={['/settings/users']}>
      <Routes>
        <Route path="/login" element={<h1>Sign in</h1>} />
        <Route element={<ProtectedRoute isAuthenticated={auth.status === 'authenticated'} />}>
          <Route path="/settings/users" element={<h1>Settings home</h1>} />
        </Route>
      </Routes>
    </MemoryRouter>
  )
}

function RefreshProbe() {
  const auth = useAuth()
  return <button onClick={() => void auth.refreshMe()}>Refresh me</button>
}

function renderProvider(children: React.ReactNode = <AuthProbe />) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <AuthSessionProvider>{children}</AuthSessionProvider>
    </QueryClientProvider>,
  )
  return queryClient
}

beforeEach(() => {
  vi.clearAllMocks()
  accessTokenStore.clear()
  mockedAuthApi.refresh.mockResolvedValue({ accessToken: 'fresh', expiresIn: 900, user: { id: '1', displayName: 'Admin' } })
  mockedAuthApi.me.mockResolvedValue(user)
})

describe('AuthSessionProvider session expiry', () => {
  it('sessionExpiredClearsAuthAndRedirects', async () => {
    const queryClient = renderProvider()

    expect(await screen.findByText('Settings home')).toBeInTheDocument()
    queryClient.setQueryData(['auth', 'me'], user)

    authEvents.emit()

    expect(accessTokenStore.get()).toBeNull()
    await waitFor(() => {
      expect(queryClient.getQueryData(['auth', 'me'])).toBeUndefined()
      expect(screen.getByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    })
  })

  it('sessionExpiredClearsSessionBoundQueryCache', async () => {
    const queryClient = renderProvider()

    expect(await screen.findByText('Settings home')).toBeInTheDocument()
    queryClient.setQueryData(['auth', 'me'], user)
    queryClient.setQueryData(['settings', 'users', 0, 50], { sensitive: 'stale user data' })

    authEvents.emit()

    expect(accessTokenStore.get()).toBeNull()
    await waitFor(() => {
      expect(queryClient.getQueryData(['auth', 'me'])).toBeUndefined()
      expect(queryClient.getQueryData(['settings', 'users', 0, 50])).toBeUndefined()
      expect(screen.getByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    })
    expect(mockedAuthApi.refresh).toHaveBeenCalledTimes(1)
    expect(mockedAuthApi.me).toHaveBeenCalledTimes(1)
  })

  it('sessionExpiredDoesNotStartRefreshLoop', async () => {
    renderProvider()
    await screen.findByText('Settings home')

    authEvents.emit()
    authEvents.emit()
    authEvents.emit()

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Sign in' })).toBeInTheDocument())
    expect(mockedAuthApi.refresh).toHaveBeenCalledTimes(1)
    expect(mockedAuthApi.me).toHaveBeenCalledTimes(1)
  })
  it('bootstrapRefreshRaceShowsGuidanceInsteadOfSilentLogin', async () => {
    // A racing rotation never resolves: the bootstrap performs the single
    // retry the backend contract allows (first 409, brief wait, one more
    // attempt) and then stops. There is no automatic recovery -- the retry
    // lifecycle must not be extended with a second horizon, so exactly two
    // refresh calls happen, never a third or fourth.
    const config = { headers: {} } as InternalAxiosRequestConfig
    const race = () => new AxiosError(
      'race', 'ERR_BAD_RESPONSE', config, undefined,
      { config, data: { code: 'AUTH_REFRESH_RACE' }, headers: {}, status: 409, statusText: 'Conflict' } as AxiosResponse,
    )
    mockedAuthApi.refresh.mockRejectedValue(race())

    renderProvider()

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Sign in' })).toBeInTheDocument(), { timeout: 8000 })
    expect(mockedAuthApi.refresh).toHaveBeenCalledTimes(2)
    expect(mockedAuthApi.me).not.toHaveBeenCalled()
    expect(message.warning).toHaveBeenCalledWith(expect.stringMatching(/会话刷新冲突/))
  }, 10_000)


  it('bootstraps exactly one refresh under StrictMode double effects', async () => {
    // The app mounts under <StrictMode>; in dev this double-runs the bootstrap
    // effect, which used to fire two concurrent refresh calls with the same
    // rotation cookie (one always 409 AUTH_REFRESH_RACE on every page load).
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <StrictMode>
        <QueryClientProvider client={queryClient}>
          <AuthSessionProvider><AuthProbe /></AuthSessionProvider>
        </QueryClientProvider>
      </StrictMode>,
    )

    await screen.findByText('Settings home')
    expect(mockedAuthApi.refresh).toHaveBeenCalledTimes(1)
    expect(mockedAuthApi.me).toHaveBeenCalledTimes(1)
    expect(accessTokenStore.get()).toBe('fresh')
  })

  it('refreshMe refetches the me projection and keeps the session', async () => {
    renderProvider(<RefreshProbe />)
    await screen.findByText('Refresh me')

    fireEvent.click(screen.getByRole('button', { name: 'Refresh me' }))

    await waitFor(() => {
      expect(mockedAuthApi.me).toHaveBeenCalledTimes(2)
    })
    expect(accessTokenStore.get()).toBe('fresh')
  })
})