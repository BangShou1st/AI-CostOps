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
        .catch((refreshError: unknown) => {
          // One emit per shared refresh attempt: every waiter observes the
          // same single-flight failure, so the session-expired event must not
          // fire once per waiting request.
          if (isSessionTerminal(refreshError)) {
            authEvents.emit()
          }
          throw refreshError
        })
        .finally(() => {
          refreshInFlight = null
        })
    }

    try {
      await refreshInFlight
    } catch (refreshError) {
      // Only a terminal failure (the backend revoked or expired the session)
      // wipes the access token. A lost rotation race (409 AUTH_REFRESH_RACE)
      // or a transport error leaves the session alive, so the old token is
      // kept -- clearing it would force every following request through the
      // refresh path for no reason.
      if (isSessionTerminal(refreshError)) {
        options.tokenStore.clear()
      }
      return Promise.reject(refreshError)
    }
    return client.request(requestConfig).catch((retryError: unknown) => {
      if (isSessionTerminal(retryError)) {
        authEvents.emit()
      }
      return Promise.reject(retryError)
    })
  })

  return client
}

function isSessionTerminal(error: unknown): boolean {
  if (!axios.isAxiosError(error) || error.response?.status !== 401) {
    return false
  }
  const code = error.response?.data?.code
  // AUTH_SESSION_EXPIRED: the refresh session is gone. AUTH_REFRESH_REPLAY:
  // the credential was already rotated out, so the session was revoked.
  // Both are terminal -- the client must log out, never retry or swallow them.
  return code === 'AUTH_SESSION_EXPIRED' || code === 'AUTH_REFRESH_REPLAY'
}
