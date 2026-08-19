import { useQueryClient } from '@tanstack/react-query'
import { createContext, useContext, useEffect, useMemo, useRef, useState, type PropsWithChildren } from 'react'
import { message } from 'antd'
import { accessTokenStore } from './accessTokenStore'
import { authApi } from './authApi'
import { authEvents } from './authEvents'
import {
  bootstrapSession,
  crossTabAuthCoordinator,
  logoutWithCookieLock,
  refreshMe,
  RefreshRaceUnresolvedError,
  type AuthUser,
} from './authSession'
import type { CrossTabAuthCoordinator, CrossTabAuthEvent } from './crossTabAuthCoordinator'

type AuthState = { status: 'loading' | 'anonymous' | 'authenticated'; user: AuthUser | null }

interface AuthContextValue extends AuthState {
  login: (email: string, password: string) => Promise<void>
  logout: () => Promise<void>
  refreshMe: () => Promise<AuthUser>
}

const AuthContext = createContext<AuthContextValue | null>(null)

interface AuthSessionProviderProps extends PropsWithChildren {
  coordinator?: CrossTabAuthCoordinator
}

export function AuthSessionProvider({ children, coordinator = crossTabAuthCoordinator }: AuthSessionProviderProps) {
  const [state, setState] = useState<AuthState>({ status: 'loading', user: null })
  const queryClient = useQueryClient()
  const terminalTransition = useRef(false)
  const remoteReplacementFlight = useRef<Promise<void> | null>(null)

  const clearLocalSession = () => {
    accessTokenStore.clear()
    void queryClient.cancelQueries()
    queryClient.clear()
    setState({ status: 'anonymous', user: null })
  }

  const handleLocalTerminal = () => {
    clearLocalSession()
    if (!terminalTransition.current) {
      terminalTransition.current = true
      coordinator.publish('SESSION_INVALIDATED')
    }
    message.warning('您的权限已变更或会话已过期，请重新登录。')
  }

  const handleRemoteReplacement = () => {
    if (remoteReplacementFlight.current) return
    const replacement = (async () => {
      terminalTransition.current = false
      accessTokenStore.clear()
      setState({ status: 'loading', user: null })
      void queryClient.cancelQueries()
      queryClient.clear()
      try {
        const user = await bootstrapSession(authApi, accessTokenStore)
        setState({ status: 'authenticated', user })
      } catch (error: unknown) {
        if (error instanceof RefreshRaceUnresolvedError) {
          message.warning('会话刷新冲突暂未解决，请稍后刷新页面重试。')
        }
        clearLocalSession()
      }
    })()
    remoteReplacementFlight.current = replacement
    void replacement.finally(() => {
      if (remoteReplacementFlight.current === replacement) remoteReplacementFlight.current = null
    })
  }

  const handleRemoteEvent = (event: CrossTabAuthEvent) => {
    if (event.type === 'SESSION_REPLACED') {
      handleRemoteReplacement()
      return
    }
    if (event.type === 'SESSION_CLEARED') {
      clearLocalSession()
      return
    }
    if (event.type === 'SESSION_INVALIDATED') {
      terminalTransition.current = true
      clearLocalSession()
    }
    // REFRESH_COMPLETED is informational. It never carries or changes a token.
  }

  useEffect(() => {
    let active = true
    bootstrapSession(authApi, accessTokenStore)
      .then((user) => {
        if (active) {
          terminalTransition.current = false
          setState({ status: 'authenticated', user })
        }
      })
      .catch((error: unknown) => {
        if (!active) return
        if (error instanceof RefreshRaceUnresolvedError) {
          // Another window/device is rotating the session; the session itself
          // is intact and must not be revoked by a stale-credential retry.
          message.warning('会话刷新冲突暂未解决，请稍后刷新页面重试。')
        }
        if (isTerminalSessionError(error)) handleLocalTerminal()
        else setState({ status: 'anonymous', user: null })
      })
    return () => { active = false }
  }, [])

  useEffect(() => {
    const unsubscribeLocal = authEvents.subscribe(handleLocalTerminal)
    const unsubscribeRemote = coordinator.subscribe(handleRemoteEvent)
    return () => {
      unsubscribeLocal()
      unsubscribeRemote()
    }
  }, [coordinator, queryClient])

  const value = useMemo<AuthContextValue>(() => ({ ...state,
    login: async (email, password) => {
      const result = await coordinator.withCookieLock(() => authApi.login(email, password))
      accessTokenStore.set(result.accessToken)
      try {
        const user = await authApi.me()
        void queryClient.cancelQueries()
        queryClient.clear()
        terminalTransition.current = false
        setState({ status: 'authenticated', user })
        coordinator.publish('SESSION_REPLACED')
      } catch (error: unknown) {
        accessTokenStore.clear()
        throw error
      }
    },
    logout: async () => {
      let succeeded = false
      try {
        await logoutWithCookieLock(authApi.logout, authApi.refresh, accessTokenStore, coordinator)
        succeeded = true
      } finally {
        clearLocalSession()
        if (succeeded) coordinator.publish('SESSION_CLEARED')
      }
    },
    refreshMe: async () => {
      const user = await refreshMe(authApi)
      setState({ status: 'authenticated', user })
      return user
    },
  }), [coordinator, queryClient, state])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

function isTerminalSessionError(error: unknown): boolean {
  if (!error || typeof error !== 'object') return false
  const response = (error as { response?: { status?: number; data?: { code?: unknown } } }).response
  return response?.status === 401
    && (response.data?.code === 'AUTH_SESSION_EXPIRED' || response.data?.code === 'AUTH_REFRESH_REPLAY')
}

export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used within AuthSessionProvider')
  return context
}
