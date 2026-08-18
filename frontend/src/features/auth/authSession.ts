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
    bootstrapFlight = doBootstrapWithRaceRecovery(api, store).finally(() => {
      bootstrapFlight = null
    })
  }
  return bootstrapFlight
}

async function doBootstrapWithRaceRecovery(api: AuthApi, store: AccessTokenStore): Promise<AuthUser> {
  try {
    return await doBootstrap(api, store)
  } catch (error) {
    if (!(error instanceof RefreshRaceUnresolvedError)) throw error
    return recoverBootstrapRace(error, () => doBootstrap(api, store))
  }
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
 * A refresh race means another window/device just rotated this session. The
 * backend tolerates the now-previous credential only within its own
 * refresh-race-window (10s by default, aicostops.auth.refresh-race-window);
 * presenting it after that window is classified as REPLAY and REVOKES the
 * session. The client therefore never re-sends the stale credential unless
 * the retry is still inside a safe horizon -- a timer delayed by tab
 * throttling, a busy main thread or OS sleep must not turn a recoverable
 * 409 into a fatal 401.
 */
export class RefreshRaceUnresolvedError extends Error {
  constructor(readonly raceObservedAt: number) {
    super('Refresh race unresolved inside the safe retry horizon')
    this.name = 'RefreshRaceUnresolvedError'
  }
}

const RACE_RETRY_WAIT_MS = 500
/** Must stay well below the backend refresh-race-window (10s default). */
const RACE_RETRY_HORIZON_MS = 3000
const RACE_RECOVERY_WAIT_MS = 1200
/** Second line of defense; still below the backend race window. */
const RACE_RECOVERY_HORIZON_MS = 5000

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
      // here; the bootstrap recovery re-reads the cookie jar shortly.
      if (isRefreshRace(retryError)) throw new RefreshRaceUnresolvedError(raceObservedAt)
      throw retryError
    }
  }
}

/**
 * One bounded recovery attempt after a race: the sibling rotation has had
 * time to reach the shared cookie jar, and the refresh re-reads the cookie
 * at request time. The horizon check still prevents any stale-credential
 * resend past the backend race window.
 */
export async function recoverBootstrapRace(
  error: RefreshRaceUnresolvedError,
  retry: () => Promise<AuthUser>,
  wait: (milliseconds: number) => Promise<void> = (milliseconds) =>
    new Promise((resolve) => window.setTimeout(resolve, milliseconds)),
  now: () => number = Date.now,
): Promise<AuthUser> {
  await wait(RACE_RECOVERY_WAIT_MS)
  if (now() - error.raceObservedAt > RACE_RECOVERY_HORIZON_MS) throw error
  return retry()
}


export async function refreshMe(api: AuthApi): Promise<AuthUser> {
  return api.me()
}