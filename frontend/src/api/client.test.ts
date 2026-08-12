import {
  AxiosError,
  type AxiosAdapter,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios'
import { describe, expect, it, vi } from 'vitest'
import { createAccessTokenStore } from '../features/auth/accessTokenStore'
import { createApiClient } from './client'

function success(config: InternalAxiosRequestConfig): Promise<AxiosResponse> {
  return Promise.resolve({ config, data: { ok: true }, headers: {}, status: 200, statusText: 'OK' })
}

function unauthorized(config: InternalAxiosRequestConfig): Promise<never> {
  const response: AxiosResponse = {
    config,
    data: { code: 'AUTH_ACCESS_EXPIRED' },
    headers: {},
    status: 401,
    statusText: 'Unauthorized',
  }
  return Promise.reject(new AxiosError('Unauthorized', 'ERR_BAD_REQUEST', config, undefined, response))
}

describe('API client', () => {
  it('uses the V1 base path and adds the in-memory access token', async () => {
    const store = createAccessTokenStore()
    store.set('access-token')
    let requestConfig: InternalAxiosRequestConfig | undefined
    const adapter: AxiosAdapter = (config) => {
      requestConfig = config
      return success(config)
    }
    const client = createApiClient({ tokenStore: store, adapter })

    await client.get('/costs')

    expect(requestConfig?.baseURL).toBe('/api/v1')
    expect(requestConfig?.headers.get('Authorization')).toBe('Bearer access-token')
  })

  it('single-flights refresh for concurrent expired requests', async () => {
    const store = createAccessTokenStore()
    store.set('expired-token')
    const attempts = new Map<string, number>()
    const adapter: AxiosAdapter = (config) => {
      const url = config.url ?? ''
      const attempt = (attempts.get(url) ?? 0) + 1
      attempts.set(url, attempt)
      return attempt === 1 ? unauthorized(config) : success(config)
    }
    let releaseRefresh: ((token: string) => void) | undefined
    const refreshAccessToken = vi.fn(() => new Promise<string>((resolve) => {
      releaseRefresh = resolve
    }))
    const client = createApiClient({ tokenStore: store, adapter, refreshAccessToken })

    const requests = Promise.all([client.get('/first'), client.get('/second')])
    await vi.waitFor(() => expect(refreshAccessToken).toHaveBeenCalledTimes(1))
    releaseRefresh?.('fresh-token')
    await requests

    expect(store.get()).toBe('fresh-token')
    expect(attempts).toEqual(new Map([['/first', 2], ['/second', 2]]))
  })

  it('retries an unauthorized request only once', async () => {
    const store = createAccessTokenStore()
    store.set('expired-token')
    let attempts = 0
    const adapter: AxiosAdapter = (config) => {
      attempts += 1
      return unauthorized(config)
    }
    const refreshAccessToken = vi.fn().mockResolvedValue('still-invalid-token')
    const client = createApiClient({ tokenStore: store, adapter, refreshAccessToken })

    await expect(client.get('/always-unauthorized')).rejects.toMatchObject({ response: { status: 401 } })

    expect(attempts).toBe(2)
    expect(refreshAccessToken).toHaveBeenCalledTimes(1)
  })
})
