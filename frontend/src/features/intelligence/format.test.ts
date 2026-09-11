import { describe, expect, it } from 'vitest'
import { formatCompactMoney, formatDateTime, formatDay, formatMoney, formatPercent } from './format'

describe('intelligence format', () => {
  it('formats decimal-string money per currency', () => {
    expect(formatMoney('12430.22', 'USD')).toBe('$12,430.22')
    expect(formatMoney('30.00', 'CNY')).toContain('30.00')
  })
  it('falls back to an em dash for missing or invalid amounts', () => {
    expect(formatMoney(null, 'USD')).toBe('—')
    expect(formatMoney('', 'USD')).toBe('—')
    expect(formatMoney('nope', 'USD')).toBe('—')
    expect(formatCompactMoney(undefined, 'USD')).toBe('—')
  })
  it('formats percent with uniform precision and sign', () => {
    expect(formatPercent('34.3')).toBe('+34.3%')
    expect(formatPercent('-5')).toBe('-5.0%')
    expect(formatPercent(null)).toBe('—')
  })
  it('formats timestamps without ambiguous numeric dates', () => {
    const text = formatDateTime('2026-09-11T02:00:00Z')
    expect(text).toContain('2026')
    expect(text).not.toMatch(/^\d{2}\/\d{2}\/\d{2}$/)
    expect(formatDay('2026-09-10')).toContain('Sep')
  })
})
