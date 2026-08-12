import axios, {
  type AxiosAdapter,
  type AxiosInstance,
  type InternalAxiosRequestConfig,
} from 'axios'
import type { AccessTokenStore } from '../features/auth/accessTokenStore'

interface ApiClientOptions {
  tokenStore: AccessTokenStore
  adapter?: AxiosAdapter
  refreshAccessToken?: () => Promise<string>
}

interface RetryableRequestConfig extends InternalAxiosRequestConfig {
  aicostopsRetried?: boolean
}

export function createApiClient(options: ApiClientOptions): AxiosInstance {
  const client = axios.create({ baseURL: '/api/v1', adapter: options.adapter })
  let refreshInFlight: Promise<string> | null = null

  client.interceptors.request.use((config) => {
    const accessToken = options.tokenStore.get()
    if (accessToken) {
      config.headers.set('Authorization', `Bearer ${accessToken}`)
    }
    return config
  })

  client.interceptors.response.use(undefined, async (error: unknown) => {
    if (!axios.isAxiosError(error) || error.response?.status !== 401 || !error.config) {
      return Promise.reject(error)
    }

    const requestConfig = error.config as RetryableRequestConfig
    if (requestConfig.aicostopsRetried || !options.refreshAccessToken) {
      return Promise.reject(error)
    }
    requestConfig.aicostopsRetried = true

    if (!refreshInFlight) {
      refreshInFlight = options.refreshAccessToken()
        .then((token) => {
          options.tokenStore.set(token)
          return token
        })
        .finally(() => {
          refreshInFlight = null
        })
    }

    await refreshInFlight
    return client.request(requestConfig)
  })

  return client
}
