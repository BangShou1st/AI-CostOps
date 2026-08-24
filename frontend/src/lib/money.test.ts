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
  it('formats display amounts with at least two decimals and a currency code suffix', () => {
    expect(formatMoney('5.00000000', 'CNY')).toBe('5.00 CNY')
    expect(formatMoney('1.25000000', 'CNY')).toBe('1.25 CNY')
    expect(formatMoney('1234.56000000', 'USD')).toBe('1,234.56 USD')
    expect(formatMoney('-1234.56700000', 'CNY')).toBe('-1,234.567 CNY')
    expect(formatMoney('1000000.00000000')).toBe('1,000,000.00')
    // Unknown currency codes still render as a suffix.
    expect(formatMoney('2.50000000', 'xyz')).toBe('2.50 XYZ')
  })

  it('preserves meaningful scale-8 precision without rounding to zero', () => {
    expect(formatMoney('1.23450000')).toBe('1.2345')
    expect(formatMoney('1.23456789', 'USD')).toBe('1.23456789 USD')
    expect(formatMoney('0.00000001', 'USD')).toBe('0.00000001 USD')
    expect(formatMoney('0.00001234', 'USD')).toBe('0.00001234 USD')
    expect(formatMoney('-0.00000001', 'CNY')).toBe('-0.00000001 CNY')
  })

  it('never loses precision on very large amounts', () => {
    const huge = '12345678901234567890.12345678'
    expect(formatMoney(huge)).toBe('12,345,678,901,234,567,890.12345678')
    expect(formatMoney('-99999999999999999999.99999999', 'CNY'))
      .toBe('-99,999,999,999,999,999,999.99999999 CNY')
  })

  it('renders missing or unparseable amounts as an em dash', () => {
    expect(formatMoney(null)).toBe('—')
    expect(formatMoney(undefined, 'CNY')).toBe('—')
    expect(formatMoney('', 'CNY')).toBe('—')
    expect(formatMoney('abc', 'CNY')).toBe('—')
    expect(formatMoney('12', 'CNY')).toBe('—')
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
