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
  it('formats timestamps in UTC with an explicit zone label', () => {
    expect(formatDateTime('2026-09-11T02:00:00Z')).toBe('2026-09-11 02:00 UTC')
    expect(formatDateTime('2026-09-11T23:59:00Z')).toBe('2026-09-11 23:59 UTC')
    expect(formatDateTime(null)).toBe('—')
    expect(formatDateTime('not-a-date')).toBe('—')
    // ICU renders the en-GB September abbreviation as "Sept"; the day must stay
    // on the UTC calendar day rather than a viewer-local day.
    expect(formatDay('2026-09-10')).toMatch(/^10 Sep/)
    expect(formatDay('2026-09-10T23:30:00Z')).toMatch(/^10 Sep/)
  })
})
