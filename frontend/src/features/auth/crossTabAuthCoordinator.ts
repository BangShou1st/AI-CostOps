export type CrossTabAuthEventType =
  | 'SESSION_REPLACED'
  | 'SESSION_CLEARED'
  | 'SESSION_INVALIDATED'
  | 'REFRESH_COMPLETED'

export interface CrossTabAuthEvent {
  version: 1
  type: CrossTabAuthEventType
  eventId: string
  sourceTabId: string
  occurredAt: number
}

export interface CrossTabAuthLockManager {
  request<T>(
    name: string,
    options: { mode: 'exclusive' },
    callback: () => Promise<T>,
  ): Promise<T>
}

export interface CrossTabAuthChannel {
  postMessage(data: unknown): void
  addEventListener(type: 'message', listener: (event: { data: unknown }) => void): void
  removeEventListener(type: 'message', listener: (event: { data: unknown }) => void): void
  close(): void
}

interface CrossTabAuthStorageEventTarget {
  addEventListener(type: 'storage', listener: (event: StorageEvent) => void): void
  removeEventListener(type: 'storage', listener: (event: StorageEvent) => void): void
}

export interface CreateCrossTabAuthCoordinatorOptions {
  tabId?: string
  lockManager?: CrossTabAuthLockManager | null
  channelFactory?: (name: string) => CrossTabAuthChannel
  broadcastChannelSupported?: boolean
  storage?: Storage | null
  storageEventTarget?: CrossTabAuthStorageEventTarget | null
  now?: () => number
  randomId?: () => string
}

export interface CrossTabAuthCoordinator {
  readonly tabId: string
  withCookieLock<T>(operation: () => Promise<T>): Promise<T>
  publish(type: CrossTabAuthEventType): void
  subscribe(listener: (event: CrossTabAuthEvent) => void): () => void
  close(): void
}

export const AUTH_COOKIE_LOCK_NAME = 'aicostops:auth:cookie'
export const AUTH_BROADCAST_CHANNEL_NAME = 'aicostops:auth'

const AUTH_STORAGE_EVENT_KEY = 'aicostops:auth:event'
const EVENT_TYPES = new Set<CrossTabAuthEventType>([
  'SESSION_REPLACED',
  'SESSION_CLEARED',
  'SESSION_INVALIDATED',
  'REFRESH_COMPLETED',
])

export function createCrossTabAuthCoordinator(
  options: CreateCrossTabAuthCoordinatorOptions = {},
): CrossTabAuthCoordinator {
  const tabId = options.tabId ?? createId()
  const now = options.now ?? Date.now
  const randomId = options.randomId ?? createId
  const listeners = new Set<(event: CrossTabAuthEvent) => void>()
  const seenEventIds = new Set<string>()
  const lockManager = options.lockManager === null
    ? undefined
    : options.lockManager ?? getBrowserLockManager()
  const channel = createChannel(options)
  // Storage is notification-only. Keep it available even when BroadcastChannel
  // was created successfully so a runtime postMessage failure can fall back to
  // the same non-secret envelope without changing the lock implementation.
  const storage = options.storage ?? getBrowserStorage()
  const storageEventTarget = options.storageEventTarget ?? getBrowserStorageEventTarget()
  let localLockTail = Promise.resolve()
  let closed = false

  const receive = (data: unknown) => {
    const event = parseEvent(data)
    if (!event || seenEventIds.has(event.eventId)) return
    seenEventIds.add(event.eventId)
    if (event.sourceTabId === tabId) return
    for (const listener of [...listeners]) listener(event)
  }

  const channelListener = (event: { data: unknown }) => receive(event.data)
  channel?.addEventListener('message', channelListener)

  const storageListener = (event: StorageEvent) => {
    if (event.key !== AUTH_STORAGE_EVENT_KEY || !event.newValue) return
    if (event.storageArea && storage && event.storageArea !== storage) return
    receive(event.newValue)
  }
  storageEventTarget?.addEventListener('storage', storageListener)

  return {
    tabId,
    withCookieLock<T>(operation: () => Promise<T>): Promise<T> {
      if (closed) return Promise.reject(new Error('CrossTabAuthCoordinator is closed'))
      if (lockManager) {
        return lockManager.request(AUTH_COOKIE_LOCK_NAME, { mode: 'exclusive' }, operation)
      }

      const previous = localLockTail
      const current = previous.then(operation, operation)
      localLockTail = current.then(() => undefined, () => undefined)
      return current
    },
    publish(type: CrossTabAuthEventType): void {
      if (closed || !EVENT_TYPES.has(type)) return
      const event: CrossTabAuthEvent = {
        version: 1,
        type,
        eventId: randomId(),
        sourceTabId: tabId,
        occurredAt: now(),
      }
      seenEventIds.add(event.eventId)
      if (channel) {
        try {
          channel.postMessage(event)
          return
        } catch {
          // A failed BroadcastChannel must not break local auth correctness.
        }
      }
      publishStorageEvent(storage, event)
    },
    subscribe(listener: (event: CrossTabAuthEvent) => void): () => void {
      if (closed) return () => undefined
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    close(): void {
      if (closed) return
      closed = true
      channel?.removeEventListener('message', channelListener)
      channel?.close()
      storageEventTarget?.removeEventListener('storage', storageListener)
      listeners.clear()
    },
  }
}

function createChannel(options: CreateCrossTabAuthCoordinatorOptions): CrossTabAuthChannel | undefined {
  if (options.broadcastChannelSupported === false) return undefined
  if (options.channelFactory) {
    try {
      return options.channelFactory(AUTH_BROADCAST_CHANNEL_NAME)
    } catch {
      return undefined
    }
  }
  if (typeof BroadcastChannel === 'undefined') return undefined
  try {
    return new BroadcastChannel(AUTH_BROADCAST_CHANNEL_NAME) as unknown as CrossTabAuthChannel
  } catch {
    return undefined
  }
}

function getBrowserLockManager(): CrossTabAuthLockManager | undefined {
  if (typeof navigator === 'undefined' || !navigator.locks) return undefined
  return {
    request: (name, requestOptions, callback) => navigator.locks.request(name, requestOptions, callback),
  }
}

function getBrowserStorage(): Storage | undefined {
  try {
    return typeof localStorage === 'undefined' ? undefined : localStorage
  } catch {
    return undefined
  }
}

function getBrowserStorageEventTarget(): CrossTabAuthStorageEventTarget | undefined {
  return typeof window === 'undefined' ? undefined : window
}

function publishStorageEvent(storage: Storage | null | undefined, event: CrossTabAuthEvent): void {
  if (!storage) return
  try {
    storage.setItem(AUTH_STORAGE_EVENT_KEY, JSON.stringify(event))
    storage.removeItem(AUTH_STORAGE_EVENT_KEY)
  } catch {
    // Storage is only a best-effort notification fallback.
  }
}

function parseEvent(data: unknown): CrossTabAuthEvent | null {
  let candidate: unknown = data
  if (typeof candidate === 'string') {
    try {
      candidate = JSON.parse(candidate) as unknown
    } catch {
      return null
    }
  }
  if (!candidate || typeof candidate !== 'object' || Array.isArray(candidate)) return null
  const record = candidate as Record<string, unknown>
  const allowedKeys = ['version', 'type', 'eventId', 'sourceTabId', 'occurredAt']
  if (Object.keys(record).some((key) => !allowedKeys.includes(key))) return null
  if (
    record.version !== 1
    || typeof record.type !== 'string'
    || !EVENT_TYPES.has(record.type as CrossTabAuthEventType)
    || typeof record.eventId !== 'string'
    || record.eventId.length === 0
    || typeof record.sourceTabId !== 'string'
    || record.sourceTabId.length === 0
    || typeof record.occurredAt !== 'number'
    || !Number.isFinite(record.occurredAt)
  ) return null
  return {
    version: 1,
    type: record.type as CrossTabAuthEventType,
    eventId: record.eventId,
    sourceTabId: record.sourceTabId,
    occurredAt: record.occurredAt,
  }
}

function createId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `${Date.now()}-${Math.random().toString(36).slice(2)}`
}
