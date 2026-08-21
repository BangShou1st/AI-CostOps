import { describe, expect, it } from 'vitest'
import {
  addDecimal8,
  compareDecimal8,
  formatDecimal8,
  formatMoney,
  parseDecimal8,
  subtractDecimal8,
  sumDecimal8,
} from './money'

describe('decimal8 money helpers', () => {
  it('formats scale-8 strings with grouping and a currency symbol', () => {
    expect(formatMoney('1234.56000000', 'USD')).toBe('$1,234.56')
    expect(formatMoney('-1234.56700000', 'CNY')).toBe('-¥1,234.567')
    expect(formatMoney('1000000.00000000')).toBe('1,000,000.00')
  })

  it('preserves meaningful scale-8 reconciliation differences', () => {
    expect(formatMoney('1.23450000', 'USD')).toBe('$1.2345')
    expect(formatMoney('0.00000001', 'USD')).toBe('$0.00000001')
    expect(formatMoney('-0.00000001', 'CNY')).toBe('-¥0.00000001')
  })

  it('parses scale-8 strings to bigint minor units', () => {
    expect(parseDecimal8('10.00000000')).toBe(1000000000n)
    expect(parseDecimal8('-1.25000000')).toBe(-125000000n)
    expect(parseDecimal8('0.00000000')).toBe(0n)
    expect(parseDecimal8('0.00000001')).toBe(1n)
  })

  it('formats bigint minor units back to scale-8 strings', () => {
    expect(formatDecimal8(1000000000n)).toBe('10.00000000')
    expect(formatDecimal8(-125000000n)).toBe('-1.25000000')
    expect(formatDecimal8(0n)).toBe('0.00000000')
    expect(formatDecimal8(1n)).toBe('0.00000001')
  })

  it('parse and format are exact inverses', () => {
    for (const value of ['10.00000000', '-1.25000000', '0.00000000', '99999999.99999999']) {
      expect(formatDecimal8(parseDecimal8(value))).toBe(value)
    }
  })

  it('rejects more than 8 fractional digits', () => {
    expect(() => parseDecimal8('1.000000009')).toThrow()
  })

  it('rejects scientific notation, NaN, Infinity, empty, and invalid chars', () => {
    expect(() => parseDecimal8('1e3')).toThrow()
    expect(() => parseDecimal8('NaN')).toThrow()
    expect(() => parseDecimal8('Infinity')).toThrow()
    expect(() => parseDecimal8('')).toThrow()
    expect(() => parseDecimal8('abc')).toThrow()
    expect(() => parseDecimal8('10')).toThrow()
    expect(() => parseDecimal8('1,000.00000000')).toThrow()
  })

  it('adds, subtracts and sums exactly without floating drift', () => {
    expect(addDecimal8(parseDecimal8('0.10000000'), parseDecimal8('0.20000000')))
      .toBe(parseDecimal8('0.30000000'))
    expect(subtractDecimal8(parseDecimal8('1.00000000'), parseDecimal8('0.70000000')))
      .toBe(parseDecimal8('0.30000000'))
    expect(sumDecimal8([
      parseDecimal8('4.00000000'),
      parseDecimal8('6.00000000'),
      parseDecimal8('-1.25000000'),
    ])).toBe(parseDecimal8('8.75000000'))
  })

  it('compares exactly', () => {
    expect(compareDecimal8(parseDecimal8('1.00000000'), parseDecimal8('1.00000000'))).toBe(0)
    expect(compareDecimal8(parseDecimal8('1.00000000'), parseDecimal8('0.99999999'))).toBeGreaterThan(0)
    expect(compareDecimal8(parseDecimal8('-1.00000000'), parseDecimal8('0.00000000'))).toBeLessThan(0)
  })
})
