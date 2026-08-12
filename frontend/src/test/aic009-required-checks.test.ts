import { describe, expect, it } from 'vitest'

describe('AIC-009 required checks proof', () => {
  it('fails intentionally so GitHub must block merge', () => {
    expect(true).toBe(false)
  })
})
