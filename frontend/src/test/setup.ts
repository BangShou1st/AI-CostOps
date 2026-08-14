import '@testing-library/jest-dom/vitest'

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

// rc-menu performs a passive-effect state refresh right after mount in jsdom;
// React reports it as an act() warning even though the update is harmless.
const originalConsoleError = console.error
console.error = (...args: unknown[]) => {
  const message = typeof args[0] === 'string' ? args[0] : ''
  if (message.includes('not wrapped in act')) return
  originalConsoleError(...args)
}

// antd popups (Select, Drawer, Dropdown) rely on ResizeObserver for layout.
class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
globalThis.ResizeObserver = globalThis.ResizeObserver ?? ResizeObserverStub
