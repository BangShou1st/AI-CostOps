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
  AuthLifecycleSupersededError,
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
  const lifecycleEpoch = useRef(0)
  const lifecycleKey = useRef<object>({})
  const mounted = useRef(false)
  const replacementRequested = useRef(false)
  const replacementRunning = useRef(false)

  const beginLifecycle = () => {
    lifecycleEpoch.current += 1
    lifecycleKey.current = {}
    terminalTransition.current = false
    return { epoch: lifecycleEpoch.current, key: lifecycleKey.current }
  }

  const isCurrentLifecycle = (epoch: number) => mounted.current && lifecycleEpoch.current === epoch

  const clearLocalSession = () => {
    accessTokenStore.clear()
    void queryClient.cancelQueries()
    queryClient.clear()
    setState({ status: 'anonymous', user: null })
  }

  const beginLocalReplacementProjection = () => {
    accessTokenStore.clear()
    void queryClient.cancelQueries()
    queryClient.clear()
    setState({ status: 'loading', user: null })
  }

  const handleLocalTerminal = () => {
    const firstTerminalTransition = !terminalTransition.current
    if (firstTerminalTransition) {
      beginLifecycle()
      terminalTransition.current = true
      replacementRequested.current = false
    }
    clearLocalSession()
    if (firstTerminalTransition) {
      coordinator.publish('SESSION_INVALIDATED')
    }
    message.warning('您的权限已变更或会话已过期，请重新登录。')
  }

  const drainRemoteReplacements = async () => {
    if (replacementRunning.current) return
    replacementRunning.current = true
    try {
      while (replacementRequested.current) {
        replacementRequested.current = false
        const epoch = lifecycleEpoch.current
        const key = lifecycleKey.current
        try {
          const user = await bootstrapSession(authApi, accessTokenStore, {
            key,
            isCurrent: () => isCurrentLifecycle(epoch),
          })
          if (!isCurrentLifecycle(epoch)) continue
          terminalTransition.current = false
          setState({ status: 'authenticated', user })
        } catch (error: unknown) {
          if (!isCurrentLifecycle(epoch)) continue
          if (error instanceof AuthLifecycleSupersededError) continue
          if (error instanceof RefreshRaceUnresolvedError) {
            message.warning('会话刷新冲突暂未解决，请稍后刷新页面重试。')
          }
          clearLocalSession()
        }
      }
    } finally {
      replacementRunning.current = false
      if (replacementRequested.current) void drainRemoteReplacements()
    }
  }

  const handleRemoteReplacement = () => {
    beginLifecycle()
    replacementRequested.current = true
    accessTokenStore.clear()
    setState({ status: 'loading', user: null })
    void queryClient.cancelQueries()
    queryClient.clear()
    void drainRemoteReplacements()
  }

  const handleRemoteEvent = (event: CrossTabAuthEvent) => {
    if (event.type === 'SESSION_REPLACED') {
      handleRemoteReplacement()
      return
    }
    if (event.type === 'SESSION_CLEARED') {
      beginLifecycle()
      replacementRequested.current = false
      clearLocalSession()
      return
    }
    if (event.type === 'SESSION_INVALIDATED') {
      beginLifecycle()
      replacementRequested.current = false
      terminalTransition.current = true
      clearLocalSession()
    }
    // REFRESH_COMPLETED is informational. It never carries or changes a token.
  }

  useEffect(() => {
    mounted.current = true
    const epoch = lifecycleEpoch.current
    const key = lifecycleKey.current
    bootstrapSession(authApi, accessTokenStore, {
      key,
      isCurrent: () => isCurrentLifecycle(epoch),
    })
      .then((user) => {
        if (isCurrentLifecycle(epoch)) {
          terminalTransition.current = false
          setState({ status: 'authenticated', user })
        }
      })
      .catch((error: unknown) => {
        if (!isCurrentLifecycle(epoch)) return
        if (error instanceof AuthLifecycleSupersededError) return
        if (error instanceof RefreshRaceUnresolvedError) {
          // Another window/device is rotating the session; the session itself
          // is intact and must not be revoked by a stale-credential retry.
          message.warning('会话刷新冲突暂未解决，请稍后刷新页面重试。')
        }
        if (isTerminalSessionError(error)) handleLocalTerminal()
        else setState({ status: 'anonymous', user: null })
      })
    return () => { mounted.current = false }
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
      const generation = beginLifecycle()
      replacementRequested.current = false
      let cookieMutationSucceeded = false
      try {
        const result = await coordinator.withCookieLock(async () => {
          const loginResult = await authApi.login(email, password)
          cookieMutationSucceeded = true
          // This event describes the completed shared-cookie mutation. It must
          // not depend on this lifecycle still being current or on /auth/me.
          coordinator.publish('SESSION_REPLACED')
          if (isCurrentLifecycle(generation.epoch)) beginLocalReplacementProjection()
          return loginResult
        })
        if (!isCurrentLifecycle(generation.epoch)) return
        accessTokenStore.set(result.accessToken)
        const user = await authApi.me()
        if (!isCurrentLifecycle(generation.epoch)) return
        void queryClient.cancelQueries()
        queryClient.clear()
        terminalTransition.current = false
        setState({ status: 'authenticated', user })
      } catch (error: unknown) {
        if (cookieMutationSucceeded && isCurrentLifecycle(generation.epoch)) clearLocalSession()
        throw error
      }
    },
    logout: async () => {
      const generation = beginLifecycle()
      replacementRequested.current = false
      let succeeded = false
      try {
        await logoutWithCookieLock(authApi.logout, authApi.refresh, accessTokenStore, coordinator)
        succeeded = true
      } catch (error: unknown) {
        if (isTerminalSessionError(error)) handleLocalTerminal()
        throw error
      } finally {
        if (succeeded && isCurrentLifecycle(generation.epoch)) {
          clearLocalSession()
        }
      }
    },
    refreshMe: async () => {
      const epoch = lifecycleEpoch.current
      const user = await refreshMe(authApi)
      if (!isCurrentLifecycle(epoch)) throw new AuthLifecycleSupersededError()
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
