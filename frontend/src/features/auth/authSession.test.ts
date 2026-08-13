import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { describe, expect, it, vi } from 'vitest'
import { createAccessTokenStore } from './accessTokenStore'
import { bootstrapSession, refreshWithRaceRetry, type AuthApi } from './authSession'

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
})
