import { describe, expect, it } from 'vitest'
import {
  createCrossTabAuthCoordinator,
  type CrossTabAuthChannel,
  type CrossTabAuthLockManager,
} from './crossTabAuthCoordinator'

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

class FakeLockManager implements CrossTabAuthLockManager {
  private tail = Promise.resolve()

  request<T>(_name: string, _options: { mode: 'exclusive' }, callback: () => Promise<T>): Promise<T> {
    const previous = this.tail
    const current = previous.then(callback, callback)
    this.tail = current.then(() => undefined, () => undefined)
    return current
  }
}

class FakeBroadcastNetwork {
  private readonly channels = new Set<FakeChannel>()

  createChannel(): CrossTabAuthChannel {
    const channel = new FakeChannel(this)
    this.channels.add(channel)
    return channel
  }

  deliver(sender: FakeChannel, data: unknown) {
    for (const channel of this.channels) {
      channel.receive(sender === channel ? data : structuredClone(data))
    }
  }

  remove(channel: FakeChannel) {
    this.channels.delete(channel)
  }
}

class FakeChannel implements CrossTabAuthChannel {
  private listener: ((event: { data: unknown }) => void) | null = null

  constructor(private readonly network: FakeBroadcastNetwork) {}

  postMessage(data: unknown) {
    this.network.deliver(this, data)
  }

  addEventListener(_type: 'message', listener: (event: { data: unknown }) => void) {
    this.listener = listener
  }

  removeEventListener(_type: 'message', listener: (event: { data: unknown }) => void) {
    if (this.listener === listener) this.listener = null
  }

  receive(data: unknown) {
    this.listener?.({ data })
  }

  close() {
    this.network.remove(this)
    this.listener = null
  }
}

class FakeStorageEventTarget {
  private readonly listeners = new Set<(event: StorageEvent) => void>()

  addEventListener(_type: 'storage', listener: (event: StorageEvent) => void) {
    this.listeners.add(listener)
  }

  removeEventListener(_type: 'storage', listener: (event: StorageEvent) => void) {
    this.listeners.delete(listener)
  }

  emit(event: StorageEvent) {
    for (const listener of this.listeners) listener(event)
  }
}

class FakeStorageNetwork {
  readonly writes: Array<{ key: string; value: string | null }> = []
  private readonly targets = new Set<FakeStorageEventTarget>()
  readonly storage = {
    setItem: (key: string, value: string) => {
      this.writes.push({ key, value })
      this.emit(key, value)
    },
    removeItem: (key: string) => {
      this.writes.push({ key, value: null })
      this.emit(key, null)
    },
  } as unknown as Storage

  createTarget() {
    const target = new FakeStorageEventTarget()
    this.targets.add(target)
    return target
  }

  private emit(key: string, newValue: string | null) {
    const event = { key, newValue, storageArea: this.storage } as StorageEvent
    for (const target of this.targets) target.emit(event)
  }
}

describe('CrossTabAuthCoordinator', () => {
  it('serializes cookie auth operations across two tabs and releases after failure', async () => {
    const locks = new FakeLockManager()
    const tabA = createCrossTabAuthCoordinator({ lockManager: locks, tabId: 'tab-a' })
    const tabB = createCrossTabAuthCoordinator({ lockManager: locks, tabId: 'tab-b' })
    const first = deferred<void>()
    let concurrent = 0
    let maxConcurrent = 0
    const enter = async (operation: Promise<void>) => {
      concurrent += 1
      maxConcurrent = Math.max(maxConcurrent, concurrent)
      try {
        await operation
      } finally {
        concurrent -= 1
      }
    }

    const owner = tabA.withCookieLock(() => enter(first.promise))
    const follower = tabB.withCookieLock(() => enter(Promise.resolve()))

    await Promise.resolve()
    expect(maxConcurrent).toBe(1)
    first.resolve()
    await Promise.all([owner, follower])
    expect(maxConcurrent).toBe(1)

    const failedOwner = tabA.withCookieLock(async () => {
      throw new Error('owner failed')
    })
    await expect(failedOwner).rejects.toThrow('owner failed')
    await expect(tabB.withCookieLock(async () => 'available')).resolves.toBe('available')
  })

  it('delivers valid broadcast events across instances, ignores self events and supports unsubscribe', () => {
    const network = new FakeBroadcastNetwork()
    const tabA = createCrossTabAuthCoordinator({
      channelFactory: () => network.createChannel(),
      tabId: 'tab-a',
    })
    const tabB = createCrossTabAuthCoordinator({
      channelFactory: () => network.createChannel(),
      tabId: 'tab-b',
    })
    const receivedA: unknown[] = []
    const receivedB: unknown[] = []
    const unsubscribeA = tabA.subscribe((event) => receivedA.push(event))
    tabB.subscribe((event) => receivedB.push(event))

    tabA.publish('SESSION_REPLACED')
    expect(receivedA).toEqual([])
    expect(receivedB).toHaveLength(1)
    expect(receivedB[0]).toMatchObject({
      version: 1,
      type: 'SESSION_REPLACED',
      sourceTabId: 'tab-a',
    })

    unsubscribeA()
    tabB.publish('REFRESH_COMPLETED')
    expect(receivedA).toEqual([])

    tabA.close()
    tabB.close()
  })

  it('ignores malformed events and rejects secret-bearing payloads', () => {
    const network = new FakeBroadcastNetwork()
    const tabA = createCrossTabAuthCoordinator({
      channelFactory: () => network.createChannel(),
      tabId: 'tab-a',
    })
    const tabB = createCrossTabAuthCoordinator({
      channelFactory: () => network.createChannel(),
      tabId: 'tab-b',
    })
    const received: unknown[] = []
    tabB.subscribe((event) => received.push(event))
    const channelA = network.createChannel() as FakeChannel

    channelA.postMessage({ version: 1, type: 'NOT_AN_EVENT', eventId: '1', sourceTabId: 'tab-a', occurredAt: Date.now() })
    channelA.postMessage({ version: 1, type: 'SESSION_CLEARED', eventId: '2', sourceTabId: 'tab-a' })
    channelA.postMessage({ version: 1, type: 'SESSION_CLEARED', eventId: '3', sourceTabId: 'tab-a', occurredAt: Date.now(), accessToken: 'secret' })
    expect(received).toEqual([])

    tabA.publish('SESSION_INVALIDATED')
    const serialized = JSON.stringify((received[0] ?? {}))
    expect(serialized).not.toMatch(/accessToken|refreshToken|token|password|credential|jwt|reset/i)
    expect(Object.keys(received[0] as object).sort()).toEqual([
      'eventId', 'occurredAt', 'sourceTabId', 'type', 'version',
    ])

    tabA.close()
    tabB.close()
    channelA.close()
  })

  it('keeps single-tab correctness without BroadcastChannel and never uses localStorage as a mutex', async () => {
    const first = deferred<void>()
    const tab = createCrossTabAuthCoordinator({
      broadcastChannelSupported: false,
      lockManager: undefined,
      tabId: 'tab-a',
    })
    const firstOperation = tab.withCookieLock(() => first.promise)
    let secondStarted = false
    const secondOperation = tab.withCookieLock(async () => {
      secondStarted = true
    })

    await Promise.resolve()
    expect(secondStarted).toBe(false)
    first.resolve()
    await Promise.all([firstOperation, secondOperation])
    expect(secondStarted).toBe(true)
    tab.close()
  })

  it('uses storage-event notification fallback across tabs without persisting auth state or creating a mutex', async () => {
    const network = new FakeStorageNetwork()
    const targetA = network.createTarget()
    const targetB = network.createTarget()
    const tabA = createCrossTabAuthCoordinator({
      broadcastChannelSupported: false,
      lockManager: null,
      storage: network.storage,
      storageEventTarget: targetA,
      tabId: 'tab-a',
    })
    const tabB = createCrossTabAuthCoordinator({
      broadcastChannelSupported: false,
      lockManager: null,
      storage: network.storage,
      storageEventTarget: targetB,
      tabId: 'tab-b',
    })
    const receivedA: unknown[] = []
    const receivedB: unknown[] = []
    tabA.subscribe((event) => receivedA.push(event))
    tabB.subscribe((event) => receivedB.push(event))

    tabA.publish('SESSION_REPLACED')

    expect(receivedA).toEqual([])
    expect(receivedB).toHaveLength(1)
    expect(Object.keys(receivedB[0] as object).sort()).toEqual([
      'eventId', 'occurredAt', 'sourceTabId', 'type', 'version',
    ])
    expect(JSON.stringify(receivedB[0])).not.toMatch(/accessToken|refreshToken|token|password|credential|jwt|reset/i)
    expect(network.writes).toEqual([
      { key: 'aicostops:auth:event', value: expect.any(String) },
      { key: 'aicostops:auth:event', value: null },
    ])
    expect(network.storage.getItem?.('aicostops:auth:event')).toBeUndefined()

    const first = deferred<void>()
    let concurrent = 0
    let maxConcurrent = 0
    const enter = async (operation: Promise<void>) => {
      concurrent += 1
      maxConcurrent = Math.max(maxConcurrent, concurrent)
      try {
        await operation
      } finally {
        concurrent -= 1
      }
    }
    const owner = tabA.withCookieLock(() => enter(first.promise))
    const follower = tabB.withCookieLock(() => enter(Promise.resolve()))
    await Promise.resolve()
    expect(maxConcurrent).toBe(2)
    first.resolve()
    await Promise.all([owner, follower])

    tabA.close()
    tabB.close()
  })
})
