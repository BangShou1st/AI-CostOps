import axios from 'axios'
import type { AccessTokenStore } from './accessTokenStore'
import {
  createCrossTabAuthCoordinator,
  type CrossTabAuthCoordinator,
} from './crossTabAuthCoordinator'

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
export const crossTabAuthCoordinator = createCrossTabAuthCoordinator()

export function refreshTokenOnce(
  refresh: () => Promise<AuthTokenResponse>,
  coordinator: CrossTabAuthCoordinator = crossTabAuthCoordinator,
): Promise<AuthTokenResponse> {
  if (!refreshFlight) {
    refreshFlight = coordinator.withCookieLock(() => refreshWithRaceRetry(refresh))
      .then((result) => {
        coordinator.publish('REFRESH_COMPLETED')
        return result
      })
      .finally(() => { refreshFlight = null })
  }
  return refreshFlight
}

/**
 * Logout must stay inside the cookie lock, but its first request can fail
 * because only the access token expired. In that case refresh the cookie
 * directly while already holding the lock; calling refreshTokenOnce here
 * would try to acquire the same non-reentrant Web Lock again.
 */
export async function logoutWithCookieLock(
  logout: () => Promise<void>,
  refresh: () => Promise<AuthTokenResponse>,
  store: AccessTokenStore,
  coordinator: CrossTabAuthCoordinator = crossTabAuthCoordinator,
): Promise<void> {
  await coordinator.withCookieLock(async () => {
    try {
      await logout()
      return
    } catch (error) {
      if (!isAccessTokenExpired(error)) throw error
      const refreshed = await refreshWithRaceRetry(refresh)
      store.set(refreshed.accessToken)
      coordinator.publish('REFRESH_COMPLETED')
      await logout()
    }
  })
}

// The whole bootstrap (refresh + me) is single-flighted per lifecycle key, so
// a StrictMode double mount shares one refresh and one me read while a newer
// lifecycle can supersede an older in-flight bootstrap.
const defaultBootstrapKey = {}
const bootstrapFlights = new Map<object, Promise<AuthUser>>()

export interface BootstrapSessionOptions {
  key?: object
  isCurrent?: () => boolean
}

export class AuthLifecycleSupersededError extends Error {
  constructor() {
    super('Authentication lifecycle was superseded')
    this.name = 'AuthLifecycleSupersededError'
  }
}

export function bootstrapSession(
  api: AuthApi,
  store: AccessTokenStore,
  options: BootstrapSessionOptions = {},
): Promise<AuthUser> {
  const key = options.key ?? defaultBootstrapKey
  const existing = bootstrapFlights.get(key)
  if (existing) return existing

  const isCurrent = options.isCurrent ?? (() => true)
  const flight = doBootstrap(api, store, isCurrent)
  const tracked = flight.finally(() => {
    if (bootstrapFlights.get(key) === tracked) bootstrapFlights.delete(key)
  })
  bootstrapFlights.set(key, tracked)
  return tracked
}

async function doBootstrap(
  api: AuthApi,
  store: AccessTokenStore,
  isCurrent: () => boolean,
): Promise<AuthUser> {
  try {
    const refreshed = await refreshTokenOnce(api.refresh)
    if (!isCurrent()) throw new AuthLifecycleSupersededError()
    store.set(refreshed.accessToken)
    const user = await api.me()
    if (!isCurrent()) throw new AuthLifecycleSupersededError()
    return user
  } catch (error) {
    if (isCurrent()) store.clear()
    throw error
  }
}

/**
 * A refresh race (409 AUTH_REFRESH_RACE) means another window/device is
 * rotating this session. The backend owns replay/race authority: presenting
 * the now-previous credential beyond its own refresh-race-window (10s by
 * default, aicostops.auth.refresh-race-window) is classified as REPLAY and
 * revokes the session. The client observes the 409 only when the backend
 * already answered -- that moment is not the start of the backend rotation
 * window -- so the client never asserts any global timeout on its own.
 * Instead it performs only the single retry the backend contract explicitly
 * allows, and a repeated race stops automatically (no second retry horizon).
 */
export class RefreshRaceUnresolvedError extends Error {
  constructor(readonly raceObservedAt: number) {
    super('Refresh race unresolved inside the safe retry horizon')
    this.name = 'RefreshRaceUnresolvedError'
  }
}

const RACE_RETRY_WAIT_MS = 500
/**
 * Elapsed-time guard for the single allowed retry: while the backend is the
 * replay/race authority, a browser timer severely delayed by tab throttling,
 * a busy main thread or OS sleep must not blindly issue that retry much
 * later, when the race may long be over. It bounds how late the retry may
 * fire; it is not a client-side replica of the backend window.
 */
const RACE_RETRY_HORIZON_MS = 3000

function isRefreshRace(error: unknown): boolean {
  return axios.isAxiosError(error) && error.response?.data?.code === 'AUTH_REFRESH_RACE'
}

function isAccessTokenExpired(error: unknown): boolean {
  return axios.isAxiosError(error)
    && error.response?.status === 401
    && error.response.data?.code === 'AUTH_ACCESS_EXPIRED'
}

export async function refreshWithRaceRetry(
  refresh: () => Promise<AuthTokenResponse>,
  wait: (milliseconds: number) => Promise<void> = (milliseconds) =>
    new Promise((resolve) => window.setTimeout(resolve, milliseconds)),
  now: () => number = Date.now,
): Promise<AuthTokenResponse> {
  try {
    return await refresh()
  } catch (error) {
    if (!isRefreshRace(error)) throw error
    const raceObservedAt = now()
    await wait(RACE_RETRY_WAIT_MS)
    if (now() - raceObservedAt > RACE_RETRY_HORIZON_MS) {
      throw new RefreshRaceUnresolvedError(raceObservedAt)
    }
    try {
      return await refresh()
    } catch (retryError) {
      // A second race means the sibling rotation is still winning: stop
      // here. The session goes anonymous with guidance; only a user-initiated
      // page reload (a new lifecycle) may bootstrap again.
      if (isRefreshRace(retryError)) throw new RefreshRaceUnresolvedError(raceObservedAt)
      throw retryError
    }
  }
}

export async function refreshMe(api: AuthApi): Promise<AuthUser> {
  return api.me()
}
