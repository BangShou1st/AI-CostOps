import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { describe, expect, it, vi } from 'vitest'
import { createAccessTokenStore } from './accessTokenStore'
import {
  bootstrapSession,
  RefreshRaceUnresolvedError,
  refreshMe,
  refreshWithRaceRetry,
  type AuthApi,
} from './authSession'

const user = { id: '1', email: 'user@example.com', displayName: 'User', organizationId: '2', organizationMemberId: '3', permissions: [] }

describe('authentication session', () => {
  it('bootstraps through refresh then me without persisting the refresh secret', async () => {
    const store = createAccessTokenStore()
    const api: AuthApi = {
      refresh: vi.fn().mockResolvedValue({ accessToken: 'access', expiresIn: 900, user }),
      me: vi.fn().mockResolvedValue(user),
    }

    await expect(bootstrapSession(api, store)).resolves.toEqual(user)
    expect(store.get()).toBe('access')
    expect(api.refresh).toHaveBeenCalledTimes(1)
    expect(api.me).toHaveBeenCalledTimes(1)
  })

  it('clears auth when bootstrap refresh has expired', async () => {
    const store = createAccessTokenStore(); store.set('stale')
    const api: AuthApi = { refresh: vi.fn().mockRejectedValue(new Error('expired')), me: vi.fn() }
    await expect(bootstrapSession(api, store)).rejects.toThrow('expired')
    expect(store.get()).toBeNull()
    expect(api.me).not.toHaveBeenCalled()
  })

  it('shares one refresh flight between concurrent bootstrap sessions', async () => {
    // React StrictMode double-mounts AuthSessionProvider in dev, which starts
    // two bootstrap sessions with the same refresh cookie. They must share a
    // single refresh request; a second concurrent rotation would 409 with
    // AUTH_REFRESH_RACE on every page load.
    const store = createAccessTokenStore()
    const api: AuthApi = {
      refresh: vi.fn().mockResolvedValue({ accessToken: 'access', expiresIn: 900, user }),
      me: vi.fn().mockResolvedValue(user),
    }

    await Promise.all([bootstrapSession(api, store), bootstrapSession(api, store)])
    expect(api.refresh).toHaveBeenCalledTimes(1)
    expect(api.me).toHaveBeenCalledTimes(1)
    expect(store.get()).toBe('access')
  })

  it('waits and retries AUTH_REFRESH_RACE exactly once', async () => {
    const config = { headers: {} } as InternalAxiosRequestConfig
    const response = { config, data: { code: 'AUTH_REFRESH_RACE' }, headers: {}, status: 409,
      statusText: 'Conflict' } as AxiosResponse
    const refresh = vi.fn()
      .mockRejectedValueOnce(new AxiosError('race', 'ERR_BAD_RESPONSE', config, undefined, response))
      .mockResolvedValueOnce({ accessToken: 'fresh', expiresIn: 900, user })
    const wait = vi.fn().mockResolvedValue(undefined)

    await expect(refreshWithRaceRetry(refresh, wait)).resolves.toMatchObject({ accessToken: 'fresh' })
    expect(refresh).toHaveBeenCalledTimes(2)
    expect(wait).toHaveBeenCalledTimes(1)
  })

  it('refreshMe returns the fresh me projection', async () => {
    const api: AuthApi = { refresh: vi.fn(), me: vi.fn().mockResolvedValue(user) }

    await expect(refreshMe(api)).resolves.toEqual(user)
    expect(api.me).toHaveBeenCalledTimes(1)
  })
})
function raceError(): AxiosError {
  const config = { headers: {} } as InternalAxiosRequestConfig
  const response = { config, data: { code: 'AUTH_REFRESH_RACE' }, headers: {}, status: 409,
    statusText: 'Conflict' } as AxiosResponse
  return new AxiosError('race', 'ERR_BAD_RESPONSE', config, undefined, response)
}

describe('refresh race horizon', () => {
  it('never resends the stale credential when the retry fires beyond the horizon', async () => {
    // A hidden tab / busy main thread can delay the 500ms timer far past the
    // backend refresh-race-window. Re-sending the now-previous credential
    // would be classified as REPLAY and REVOKE the session (401). The client
    // must give up instead of re-sending.
    let clock = 0
    const refresh = vi.fn().mockRejectedValueOnce(raceError())
    const wait = vi.fn().mockImplementation(async () => { clock += 60_000 })
    const now = () => clock

    await expect(refreshWithRaceRetry(refresh, wait, now)).rejects.toBeInstanceOf(RefreshRaceUnresolvedError)
    expect(refresh).toHaveBeenCalledTimes(1)
    expect(wait).toHaveBeenCalledTimes(1)
  })

  it('gives up after a repeated race without a third attempt', async () => {
    let clock = 0
    const refresh = vi.fn().mockRejectedValueOnce(raceError()).mockRejectedValueOnce(raceError())
    const wait = vi.fn().mockImplementation(async () => { clock += 500 })
    const now = () => clock

    await expect(refreshWithRaceRetry(refresh, wait, now)).rejects.toBeInstanceOf(RefreshRaceUnresolvedError)
    expect(refresh).toHaveBeenCalledTimes(2)
  })

  it('retries once and succeeds when the retry fires inside the horizon', async () => {
    let clock = 0
    const refresh = vi.fn()
      .mockRejectedValueOnce(raceError())
      .mockResolvedValueOnce({ accessToken: 'fresh', expiresIn: 900, user })
    const wait = vi.fn().mockImplementation(async () => { clock += 500 })
    const now = () => clock

    await expect(refreshWithRaceRetry(refresh, wait, now)).resolves.toMatchObject({ accessToken: 'fresh' })
    expect(refresh).toHaveBeenCalledTimes(2)
  })

  it('throws a plain failure (401 replay / expired) straight through without retrying', async () => {
    const config = { headers: {} } as InternalAxiosRequestConfig
    const response = { config, data: { code: 'AUTH_REFRESH_REPLAY' }, headers: {}, status: 401,
      statusText: 'Unauthorized' } as AxiosResponse
    const refresh = vi.fn().mockRejectedValueOnce(new AxiosError('replay', 'ERR_BAD_RESPONSE', config, undefined, response))
    const wait = vi.fn()

    await expect(refreshWithRaceRetry(refresh, wait)).rejects.toMatchObject({ message: 'replay' })
    expect(refresh).toHaveBeenCalledTimes(1)
    expect(wait).not.toHaveBeenCalled()
  })

  it('throws 401 AUTH_SESSION_EXPIRED straight through without a race retry', async () => {
    const config = { headers: {} } as InternalAxiosRequestConfig
    const response = { config, data: { code: 'AUTH_SESSION_EXPIRED' }, headers: {}, status: 401,
      statusText: 'Unauthorized' } as AxiosResponse
    const refresh = vi.fn().mockRejectedValueOnce(new AxiosError('expired', 'ERR_BAD_RESPONSE', config, undefined, response))
    const wait = vi.fn()

    await expect(refreshWithRaceRetry(refresh, wait)).rejects.toMatchObject({ message: 'expired' })
    expect(refresh).toHaveBeenCalledTimes(1)
    expect(wait).not.toHaveBeenCalled()
  })
})


describe('bootstrap stops after the allowed race retry', () => {
  it('never opens a second retry horizon after two raced refresh attempts', async () => {
    // The backend contract allows exactly one refresh retry after
    // AUTH_REFRESH_RACE (brief wait, one more attempt). A repeated race must
    // terminate the bootstrap: an automatic recovery that waited and ran
    // refreshWithRaceRetry again would arm a fresh raceObservedAt and extend
    // the retry lifecycle beyond the single retry the backend permits.
    // The third mock below would succeed -- it must never be consumed.
    const store = createAccessTokenStore(); store.set('stale')
    const api: AuthApi = {
      refresh: vi.fn()
        .mockRejectedValueOnce(raceError())
        .mockRejectedValueOnce(raceError())
        .mockResolvedValueOnce({ accessToken: 'fresh2', expiresIn: 900, user }),
      me: vi.fn().mockResolvedValue(user),
    }

    await expect(bootstrapSession(api, store)).rejects.toBeInstanceOf(RefreshRaceUnresolvedError)
    expect(api.refresh).toHaveBeenCalledTimes(2)
    expect(api.me).not.toHaveBeenCalled()
    expect(store.get()).toBeNull()
  }, 10_000)

  it('bootstraps successfully when the single allowed retry wins', async () => {
    const store = createAccessTokenStore()
    const api: AuthApi = {
      refresh: vi.fn()
        .mockRejectedValueOnce(raceError())
        .mockResolvedValueOnce({ accessToken: 'fresh2', expiresIn: 900, user }),
      me: vi.fn().mockResolvedValue(user),
    }

    await expect(bootstrapSession(api, store)).resolves.toEqual(user)
    expect(api.refresh).toHaveBeenCalledTimes(2)
    expect(store.get()).toBe('fresh2')
  }, 10_000)
})