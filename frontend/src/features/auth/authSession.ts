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
