import { describe, expect, it } from 'vitest'
import { formatBillingPeriodLabel, formatBusinessDate, formatBusinessDateRange, formatEventDateTime } from './dateTime'

describe('date/time presentation', () => {
  it('keeps business dates date-only even when the API sends ISO midnight', () => {
    expect(formatBusinessDate('2026-08-01T00:00:00Z')).toBe('2026-08-01')
    expect(formatBusinessDateRange('2026-08-01T00:00:00Z', '2026-09-01T00:00:00Z'))
      .toBe('2026-08-01 ～ 2026-09-01')
  })

  it('formats event instants as a consistent Chinese-readable date/time', () => {
    expect(formatEventDateTime('2026-08-19T10:14:00Z')).toBe('2026-08-19 18:14')
  })

  it('formats BillingPeriod as a date-only range with a localized status', () => {
    const label = formatBillingPeriodLabel('2026-08-01T00:00:00Z', '2026-09-01T00:00:00Z', 'OPEN')
    expect(label).toBe('2026-08-01 ～ 2026-09-01（开放 · OPEN）')
    expect(label).not.toContain('T00:00:00Z')
  })
})
