import dayjs from 'dayjs'
import { describe, expect, it } from 'vitest'
import { endAfterStart, toInstant } from './rulesDateTime'

describe('toInstant', () => {
  it('serializes local datetimes as explicit millisecond-precision UTC instants', () => {
    const instant = toInstant(dayjs('2026-08-21T08:00:00'))
    expect(instant).toBe(dayjs('2026-08-21T08:00:00').toISOString())
    expect(instant!.endsWith('.000Z')).toBe(true)
    expect(instant).not.toBe('2026-08-21T08:00:00')
  })

  it('round-trips back to the picked local calendar date in any timezone', () => {
    for (const date of ['2026-01-01', '2026-08-21', '2027-02-28']) {
      const instant = toInstant(dayjs(`${date}T00:00:00`))!
      expect(dayjs(instant).format('YYYY-MM-DD')).toBe(date)
    }
  })

  it('maps missing values to null so an optional end stays absent', () => {
    expect(toInstant(null)).toBeNull()
    expect(toInstant(undefined)).toBeNull()
  })
})

describe('endAfterStart', () => {
  const build = (from: dayjs.Dayjs | null) => {
    const { validator } = endAfterStart('effectiveFrom')({ getFieldValue: () => from })
    return (value: dayjs.Dayjs | null) => validator({}, value)
  }

  it('accepts a missing or unbounded end time', async () => {
    const start = dayjs('2026-08-21T08:00:00')
    await expect(build(start)(null)).resolves.toBeUndefined()
    await expect(build(null)(start)).resolves.toBeUndefined()
    await expect(build(null)(null)).resolves.toBeUndefined()
  })

  it('accepts an end strictly after the start', async () => {
    await expect(build(dayjs('2026-08-21T08:00:00'))(dayjs('2026-09-01T00:00:00')))
      .resolves.toBeUndefined()
  })

  it('rejects an end equal to or before the start with a field-level message', async () => {
    const start = dayjs('2026-08-21T08:00:00')
    await expect(build(start)(start)).rejects.toThrow('失效时间必须晚于生效时间')
    await expect(build(start)(dayjs('2026-08-20T08:00:00'))).rejects.toThrow('失效时间必须晚于生效时间')
  })
})
