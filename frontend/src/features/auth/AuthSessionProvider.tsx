import { useQueryClient } from '@tanstack/react-query'
import { createContext, useContext, useEffect, useMemo, useState, type PropsWithChildren } from 'react'
import { accessTokenStore } from './accessTokenStore'
import { authApi } from './authApi'
import { bootstrapSession, type AuthUser } from './authSession'

type AuthState = { status: 'loading' | 'anonymous' | 'authenticated'; user: AuthUser | null }

interface AuthContextValue extends AuthState {
  login: (email: string, password: string) => Promise<void>
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthSessionProvider({ children }: PropsWithChildren) {
  const [state, setState] = useState<AuthState>({ status: 'loading', user: null })
  const queryClient = useQueryClient()

  useEffect(() => {
    let active = true
    bootstrapSession(authApi, accessTokenStore)
      .then((user) => { if (active) setState({ status: 'authenticated', user }) })
      .catch(() => { if (active) setState({ status: 'anonymous', user: null }) })
    return () => { active = false }
  }, [])

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
  }), [state, queryClient])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used within AuthSessionProvider')
  return context
}
