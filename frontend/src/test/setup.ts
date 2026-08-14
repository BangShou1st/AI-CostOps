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

// antd popups (Select, Drawer, Dropdown) rely on ResizeObserver for layout.
class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
globalThis.ResizeObserver = globalThis.ResizeObserver ?? ResizeObserverStub
