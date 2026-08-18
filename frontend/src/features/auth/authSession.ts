import axios from 'axios'
import type { AccessTokenStore } from './accessTokenStore'

export interface AuthUser {
  id: string
  email: string
  displayName: string
  organizationId: string
  organizationMemberId: string
  permissions: string[]
}

export interface AuthTokenResponse {
  accessToken: string
  expiresIn: number
  user: Pick<AuthUser, 'id' | 'displayName'>
}

export interface AuthApi {
  refresh: () => Promise<AuthTokenResponse>
  me: () => Promise<AuthUser>
}

// One shared refresh flight across all initiators (bootstrap, 401 recovery).
// Concurrent initiators present the same rotation cookie; without this they
// would race the backend rotation and one of them would 409 with
// AUTH_REFRESH_RACE on every page load (React StrictMode double-mounts the
// session bootstrap in dev).
let refreshFlight: Promise<AuthTokenResponse> | null = null

export function refreshTokenOnce(refresh: () => Promise<AuthTokenResponse>): Promise<AuthTokenResponse> {
  if (!refreshFlight) {
    refreshFlight = refreshWithRaceRetry(refresh).finally(() => {
      refreshFlight = null
    })
  }
  return refreshFlight
}

// The whole bootstrap (refresh + me) is single-flighted too, so a StrictMode
// double mount performs exactly one refresh and one me projection read.
let bootstrapFlight: Promise<AuthUser> | null = null

export function bootstrapSession(api: AuthApi, store: AccessTokenStore): Promise<AuthUser> {
  if (!bootstrapFlight) {
    bootstrapFlight = doBootstrap(api, store).finally(() => {
      bootstrapFlight = null
    })
  }
  return bootstrapFlight
}

async function doBootstrap(api: AuthApi, store: AccessTokenStore): Promise<AuthUser> {
  try {
    const refreshed = await refreshTokenOnce(api.refresh)
    store.set(refreshed.accessToken)
    return await api.me()
  } catch (error) {
    store.clear()
    throw error
  }
}

export async function refreshWithRaceRetry(
  refresh: () => Promise<AuthTokenResponse>,
  wait: (milliseconds: number) => Promise<void> = (milliseconds) =>
    new Promise((resolve) => window.setTimeout(resolve, milliseconds)),
): Promise<AuthTokenResponse> {
  try {
    return await refresh()
  } catch (error) {
    if (!axios.isAxiosError(error) || error.response?.data?.code !== 'AUTH_REFRESH_RACE') throw error
    await wait(500)
    return refresh()
  }
}

export async function refreshMe(api: AuthApi): Promise<AuthUser> {
  return api.me()
}
