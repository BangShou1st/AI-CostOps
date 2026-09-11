import { describe, expect, it } from 'vitest'
import { absDecimal, compareDecimal, formatDecimal, formatMoneyExact, parseDecimal, ratioOf, sortByAmountDesc } from './decimal'

describe('decimal-string primitives', () => {
  it('parses exact parts without Number', () => {
    expect(parseDecimal('12430.22')).toEqual({ negative: false, int: '12430', frac: '22' })
    expect(parseDecimal('-0.50')).toEqual({ negative: true, int: '0', frac: '50' })
    expect(parseDecimal('42')).toEqual({ negative: false, int: '42', frac: '' })
    expect(parseDecimal(null)).toBeNull()
    expect(parseDecimal('')).toBeNull()
    expect(parseDecimal('12.3.4')).toBeNull()
    expect(parseDecimal('abc')).toBeNull()
  })
  it('orders large integers exactly where float loses precision', () => {
    expect(compareDecimal('9007199254740993', '9007199254740992')).toBe(1)
    expect(compareDecimal('123456789012345678901234567890.01', '123456789012345678901234567889.99')).toBe(1)
    expect(compareDecimal('0.30000000000000004', '0.3')).toBe(1)
    expect(compareDecimal('2.10', '2.1')).toBe(0)
    expect(compareDecimal('-5.00', '-4.99')).toBe(-1)
    expect(compareDecimal('-0.01', '0')).toBe(-1)
    expect(compareDecimal('0', '0.00')).toBe(0)
  })
  it('formats money exactly with grouping and rounding', () => {
    expect(formatMoneyExact('12430.22', 'USD')).toBe('$12,430.22')
    expect(formatMoneyExact('12430.225', 'USD')).toBe('$12,430.23')
    expect(formatMoneyExact('999.995', 'USD')).toBe('$1,000.00')
    expect(formatMoneyExact('12345678901234567890.126', 'USD')).toBe('$12,345,678,901,234,567,890.13')
    expect(formatMoneyExact('-12.5', 'CNY')).toBe('¥-12.50')
    expect(formatMoneyExact('-0.001', 'USD')).toBe('$0.00')
    expect(formatMoneyExact('30.00', 'XXX')).toBe('30.00 XXX')
    expect(formatMoneyExact('nope', 'USD')).toBeNull()
    expect(formatMoneyExact(null, 'USD')).toBeNull()
  })
  it('sorts presentation lists without float ordering', () => {
    const rows = [{ v: '0.3' }, { v: '0.30000000000000004' }, { v: '100' }, { v: '-5' }]
    expect(sortByAmountDesc(rows, (r) => r.v).map((r) => r.v)).toEqual(['100', '0.30000000000000004', '0.3', '-5'])
  })
  it('computes non-authoritative visual ratios only', () => {
    expect(ratioOf('12430.22', '20000.00')).toBeCloseTo(0.6215, 4)
    expect(ratioOf('16820.45', '20000.00')).toBeCloseTo(0.841, 3)
    expect(ratioOf('5', '0')).toBeNull()
    expect(ratioOf('-5', '100')).toBeNull()
    expect(ratioOf('abc', '100')).toBeNull()
  })
  it('takes absolute decimal magnitudes', () => {
    expect(absDecimal('-310.13')).toBe('310.13')
    expect(absDecimal('4')).toBe('4')
    expect(absDecimal('nope')).toBe('0')
  })
  it('rounds frac parts exactly', () => {
    expect(formatDecimal('2.345', 2)).toBe('2.35')
    expect(formatDecimal('2.335', 2)).toBe('2.34')
    expect(formatDecimal('0', 2)).toBe('0.00')
  })
})
