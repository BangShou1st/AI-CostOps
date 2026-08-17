import axios from 'axios'
import { createApiClient } from '../../api/client'
import { accessTokenStore } from './accessTokenStore'
import { refreshTokenOnce, type AuthTokenResponse, type AuthUser } from './authSession'

const cookieClient = axios.create({ baseURL: '/api/v1', withCredentials: true })

async function refreshRequest(): Promise<AuthTokenResponse> {
  return (await cookieClient.post<AuthTokenResponse>('/auth/refresh')).data
}

export const apiClient = createApiClient({
  tokenStore: accessTokenStore,
  refreshAccessToken: async () => (await refreshTokenOnce(refreshRequest)).accessToken,
})

export const authApi = {
  async login(email: string, password: string) {
    return (await cookieClient.post<AuthTokenResponse>('/auth/login', { email, password })).data
  },
  refresh: refreshRequest,
  async me() { return (await apiClient.get<AuthUser>('/auth/me')).data },
  async register(email: string, displayName: string, password: string) {
    await cookieClient.post('/auth/register', { email, displayName, password })
  },
  async acceptInvitation(token: string, displayName: string, password: string) {
    await cookieClient.post(`/invitations/${encodeURIComponent(token)}/accept`, { displayName, password })
  },
  async forgotPassword(email: string) { await cookieClient.post('/auth/password/forgot', { email }) },
  async resetPassword(token: string, newPassword: string) {
    await cookieClient.post('/auth/password/reset', { token, newPassword })
  },
  async logout() { await apiClient.post('/auth/logout') },
}
