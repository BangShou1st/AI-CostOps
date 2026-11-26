import '@testing-library/jest-dom/vitest'
import { QueryClient, timeoutManager } from '@tanstack/react-query'
import { act, cleanup } from '@testing-library/react'
import { ConfigProvider } from 'antd'
import type { actDestroy as AntdActDestroy, actWrapper as AntdActWrapper } from 'antd/lib/message'
import { createElement } from 'react'
import type { Container, Root, RootOptions } from 'react-dom/client'
import { afterEach, vi } from 'vitest'

const nodeProcess = (globalThis as unknown as {
  process?: { getBuiltinModule?: (id: string) => unknown }
}).process
const nodeModule = nodeProcess?.getBuiltinModule?.('node:module') as
  | { createRequire?: (url: string) => (id: string) => unknown }
  | undefined
const nodeRequire = nodeModule?.createRequire?.(import.meta.url)

// Track every React root (including standalone antd roots created by
// Modal.confirm, WaveEffect, message, and notification via @rc-component/util)
// so afterEach unmounts all roots rather than only @testing-library/react roots.
const activeReactRoots = new Set<Root>()
if (nodeRequire) {
  const reactDomClient = nodeRequire('react-dom/client') as {
    createRoot: (container: Container, options?: RootOptions) => Root
  }
  const originalCreateRoot = reactDomClient.createRoot
  reactDomClient.createRoot = (container: Container, options?: RootOptions) => {
    const root = originalCreateRoot(container, options)
    activeReactRoots.add(root)
    const originalUnmount = root.unmount.bind(root)
    root.unmount = () => {
      activeReactRoots.delete(root)
      return originalUnmount()
    }
    return root
  }
}

const antdModalDestroyFns = nodeRequire
  ? (nodeRequire('antd/lib/modal/destroyFns') as { default: Array<() => void> }).default
  : []
const antdMessageModule = nodeRequire
  ? (nodeRequire('antd/lib/message') as {
      actWrapper?: typeof AntdActWrapper
      actDestroy?: typeof AntdActDestroy
    })
  : {}
const antdNotificationModule = nodeRequire
  ? (nodeRequire('antd/lib/notification') as {
      actWrapper?: typeof AntdActWrapper
      actDestroy?: typeof AntdActDestroy
    })
  : {}
antdMessageModule.actWrapper?.((fn) => { act(fn) })
antdNotificationModule.actWrapper?.((fn) => { act(fn) })

// Disable ripple waves and CSS transitions in antd imperative holders
// (Modal.confirm, message, notification). jsdom reports TransitionEvent
// support without firing transitionend events, which otherwise leaves
// Modal.confirm stuck in leave motion so its standalone root never unmounts.
ConfigProvider.config({
  holderRender: (children) =>
    createElement(
      ConfigProvider,
      { wave: { disabled: true }, theme: { token: { motion: false } } },
      children,
    ),
})

// Track mounted QueryClients and TanStack Query timers so GC/refetch timers
// (e.g. 300s gcTime) are deterministically cancelled at the end of every test.
const activeQueryClients = new Set<QueryClient>()
const originalQueryClientMount = QueryClient.prototype.mount
QueryClient.prototype.mount = function mountWithTracking(this: QueryClient) {
  activeQueryClients.add(this)
  return originalQueryClientMount.call(this)
}

const activeQueryTimeoutIds = new Set<ReturnType<typeof setTimeout>>()
const activeQueryIntervalIds = new Set<ReturnType<typeof setInterval>>()
timeoutManager.setTimeoutProvider({
  setTimeout(callback, delay) {
    const id = globalThis.setTimeout(() => {
      activeQueryTimeoutIds.delete(id)
      callback()
    }, delay)
    activeQueryTimeoutIds.add(id)
    return id
  },
  clearTimeout(id) {
    if (id !== undefined) {
      activeQueryTimeoutIds.delete(id as ReturnType<typeof setTimeout>)
    }
    globalThis.clearTimeout(id)
  },
  setInterval(callback, delay) {
    const id = globalThis.setInterval(callback, delay)
    activeQueryIntervalIds.add(id)
    return id
  },
  clearInterval(id) {
    if (id !== undefined) {
      activeQueryIntervalIds.delete(id as ReturnType<typeof setInterval>)
    }
    globalThis.clearInterval(id)
  },
})

// Track pending requestAnimationFrame callbacks (e.g. CSSMotion, Wave,
// scrollTo, useNoticeTimer, useDelayState) so none fire after jsdom teardown.
const activeRafIds = new Set<number>()
if (typeof globalThis.requestAnimationFrame === 'function') {
  const originalRAF = globalThis.requestAnimationFrame.bind(globalThis)
  const originalCAF = globalThis.cancelAnimationFrame.bind(globalThis)
  const trackedRAF = (callback: FrameRequestCallback): number => {
    const id = originalRAF((timestamp) => {
      activeRafIds.delete(id)
      callback(timestamp)
    })
    activeRafIds.add(id)
    return id
  }
  const trackedCAF = (id: number): void => {
    activeRafIds.delete(id)
    originalCAF(id)
  }
  globalThis.requestAnimationFrame = trackedRAF
  globalThis.cancelAnimationFrame = trackedCAF
  if (typeof window !== 'undefined' && (window as unknown) !== globalThis) {
    window.requestAnimationFrame = trackedRAF
    window.cancelAnimationFrame = trackedCAF
  }
}

// Configurable matchMedia: desktop (true) by default so layout tests exercise
// the permanent sidebar; mobile tests call setMediaMatches to flip queries.
let mediaMatches: Record<string, boolean> = {}

export function setMediaMatches(matches: Record<string, boolean>) {
  mediaMatches = { ...matches }
}

export function resetMediaMatches() {
  mediaMatches = {}
}

Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => ({
    matches: mediaMatches[query] ?? true,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  }),
})

// antd popups (Select, Drawer, Dropdown) rely on ResizeObserver for layout.
class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
globalThis.ResizeObserver = globalThis.ResizeObserver ?? ResizeObserverStub

// Guard against lingering asynchronous UI library timers (e.g. rc-component useDelayState)
// that fire after jsdom environment teardown, which would throw "ReferenceError: window is not defined".
if (typeof window !== 'undefined') {
  const domWindow = window
  Object.defineProperty(globalThis, 'window', {
    get: () => domWindow,
    set: () => {},
    configurable: true,
  })
}

function cleanupDomAndAsyncState() {
  // Unmount Testing Library roots first, then any standalone roots (e.g. antd
  // Modal.confirm, WaveEffect, message, notification) that cleanup() ignores.
  cleanup()
  for (const root of [...activeReactRoots]) {
    act(() => {
      root.unmount()
    })
  }
  antdModalDestroyFns.length = 0
  antdMessageModule.actDestroy?.()
  antdNotificationModule.actDestroy?.()

  for (const id of [...activeRafIds]) {
    cancelAnimationFrame(id)
  }

  for (const client of [...activeQueryClients]) {
    client.clear()
  }
  activeQueryClients.clear()

  for (const id of [...activeQueryTimeoutIds]) {
    globalThis.clearTimeout(id)
    activeQueryTimeoutIds.delete(id)
  }
  for (const id of [...activeQueryIntervalIds]) {
    globalThis.clearInterval(id)
    activeQueryIntervalIds.delete(id)
  }
}

// antd Form/Button schedule short `useDelayState` timers (0–10 ms) when form
// values or errors change. After unmounting all React roots and cancelling rAF
// loops, yield one short macrotask so any remaining 0–10 ms UI timers run while
// the DOM still exists, run a second cleanup pass in case a timer mounted work,
// then flush React 19 scheduler's setImmediate turns before jsdom teardown.
afterEach(async () => {
  if (vi.isFakeTimers()) {
    vi.clearAllTimers()
    vi.useRealTimers()
  }
  await Promise.resolve()
  cleanupDomAndAsyncState()
  await new Promise((resolve) => setTimeout(resolve, 20))
  cleanupDomAndAsyncState()
  if (typeof document !== 'undefined' && document.body) {
    document.body.replaceChildren()
  }
  const flushImmediate = (globalThis as unknown as {
    setImmediate?: (callback: () => void) => void
  }).setImmediate
  if (flushImmediate) {
    for (let i = 0; i < 3; i++) {
      await new Promise<void>((resolve) => flushImmediate(resolve))
    }
  }
})
