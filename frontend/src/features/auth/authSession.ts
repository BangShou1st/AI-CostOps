import axios from 'axios'
import type { AccessTokenStore } from './accessTokenStore'

export interface AuthUser {
  id: string
  email: string
  displayName: string
  organizationId: string
  organizationMemberId: string
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

export async function bootstrapSession(api: AuthApi, store: AccessTokenStore): Promise<AuthUser> {
  try {
    const refreshed = await refreshWithRaceRetry(api.refresh)
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
