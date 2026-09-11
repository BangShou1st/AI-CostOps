import { describe, expect, it } from 'vitest'
import { percentOf } from './decimal'

describe('percentOf exact ticks', () => {
  it('computes budget fractions without float', () => {
    expect(percentOf('20000.00', 25)).toBe('5000.00')
    expect(percentOf('20000.00', 50)).toBe('10000.00')
    expect(percentOf('20000.00', 75)).toBe('15000.00')
    expect(percentOf('20000.00', 90)).toBe('18000.00')
    expect(percentOf('20000.00', 100)).toBe('20000.00')
    expect(percentOf('1.005', 100)).toBe('1.005')
    expect(percentOf('0.29', 100)).toBe('0.29')
  })
  it('rejects invalid inputs', () => {
    expect(percentOf(null, 50)).toBeNull()
    expect(percentOf('abc', 50)).toBeNull()
    expect(percentOf('100', -1)).toBeNull()
    expect(percentOf('100', 12.5)).toBeNull()
  })
})
