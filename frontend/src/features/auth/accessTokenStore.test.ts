import { describe, expect, it } from 'vitest'
import { createAccessTokenStore } from './accessTokenStore'

describe('access token store', () => {
  it('keeps the token in memory and clears it', () => {
    const store = createAccessTokenStore()

    expect(store.get()).toBeNull()
    store.set('access-token')
    expect(store.get()).toBe('access-token')
    store.clear()
    expect(store.get()).toBeNull()
  })

  it('does not share tokens between independent stores', () => {
    const first = createAccessTokenStore()
    const second = createAccessTokenStore()

    first.set('first-tab-token')

    expect(second.get()).toBeNull()
  })
})
