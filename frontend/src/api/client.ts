import axios, {
  type AxiosAdapter,
  type AxiosInstance,
  type InternalAxiosRequestConfig,
} from 'axios'
import type { AccessTokenStore } from '../features/auth/accessTokenStore'
import { authEvents } from '../features/auth/authEvents'

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

    try {
      await refreshInFlight
    } catch (refreshError) {
      if (isSessionExpired(refreshError)) {
        authEvents.emit()
      }
      options.tokenStore.clear()
      return Promise.reject(refreshError)
    }
    return client.request(requestConfig).catch((retryError: unknown) => {
      if (isSessionExpired(retryError)) {
        authEvents.emit()
      }
      return Promise.reject(retryError)
    })
  })

  return client
}

function isSessionExpired(error: unknown): boolean {
  return axios.isAxiosError(error)
    && error.response?.status === 401
    && error.response?.data?.code === 'AUTH_SESSION_EXPIRED'
}
