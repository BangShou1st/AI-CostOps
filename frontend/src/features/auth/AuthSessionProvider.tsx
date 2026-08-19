import { useQueryClient } from '@tanstack/react-query'
import { createContext, useContext, useEffect, useMemo, useState, type PropsWithChildren } from 'react'
import { message } from 'antd'
import { accessTokenStore } from './accessTokenStore'
import { authApi } from './authApi'
import { authEvents } from './authEvents'
import { bootstrapSession, refreshMe, RefreshRaceUnresolvedError, type AuthUser } from './authSession'

type AuthState = { status: 'loading' | 'anonymous' | 'authenticated'; user: AuthUser | null }

interface AuthContextValue extends AuthState {
  login: (email: string, password: string) => Promise<void>
  logout: () => Promise<void>
  refreshMe: () => Promise<AuthUser>
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthSessionProvider({ children }: PropsWithChildren) {
  const [state, setState] = useState<AuthState>({ status: 'loading', user: null })
  const queryClient = useQueryClient()

  useEffect(() => {
    let active = true
    bootstrapSession(authApi, accessTokenStore)
      .then((user) => { if (active) setState({ status: 'authenticated', user }) })
      .catch((error: unknown) => {
        if (!active) return
        if (error instanceof RefreshRaceUnresolvedError) {
          // Another window/device is rotating the session; the session itself
          // is intact and must not be revoked by a stale-credential retry.
          message.warning('会话刷新冲突暂未解决，请稍后刷新页面重试。')
        }
        setState({ status: 'anonymous', user: null })
      })
    return () => { active = false }
  }, [])

  useEffect(() => {
    return authEvents.subscribe(() => {
      accessTokenStore.clear()
      // Session expiry is a security boundary: drop every query cache entry
      // (auth AND session-bound settings data), never just the auth keys.
      void queryClient.cancelQueries()
      queryClient.clear()
      setState({ status: 'anonymous', user: null })
      message.warning('您的权限已变更或会话已过期，请重新登录。')
    })
  }, [queryClient])

  const value = useMemo<AuthContextValue>(() => ({ ...state,
    login: async (email, password) => {
      const result = await authApi.login(email, password)
      accessTokenStore.set(result.accessToken)
      const user = await authApi.me()
      setState({ status: 'authenticated', user })
    },
    logout: async () => {
      try { await authApi.logout() } finally {
        accessTokenStore.clear(); queryClient.clear(); setState({ status: 'anonymous', user: null })
      }
    },
    refreshMe: async () => {
      const user = await refreshMe(authApi)
      setState({ status: 'authenticated', user })
      return user
    },
  }), [state, queryClient])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used within AuthSessionProvider')
  return context
}
