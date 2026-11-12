import '@testing-library/jest-dom/vitest'
import { afterEach, vi } from 'vitest'

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

// antd Form/Button schedule short `useDelayState` timers (0–10 ms) when form
// values or errors change. A test that ends right after such a change leaves
// the timer pending; it then fires after jsdom teardown and Vitest fails the
// whole file with an unhandled "window is not defined" error (observed on CI in
// ProviderImportUploadModal.test.tsx). Yield one short macrotask after every
// test so those timers run while the DOM still exists. Fake-timer tests are
// switched back to real timers first, otherwise this wait would itself be
// faked and never resolve.
afterEach(async () => {
  if (vi.isFakeTimers()) {
    vi.useRealTimers()
  }
  await new Promise((resolve) => setTimeout(resolve, 20))
})
