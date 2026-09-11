import { describe, expect, it } from 'vitest'
import { parseDriversJson } from './drivers'

describe('anomaly drivers parser', () => {
  it('parses the M18 dimension/key/delta shape', () => {
    const raw = JSON.stringify([
      { dimension: 'PROJECT', key: 'project:atlas', delta: '223.45' },
      { dimension: 'TEAM', key: 'team:core', delta: '86.68' },
    ])
    expect(parseDriversJson(raw)).toEqual({
      ok: true,
      drivers: [
        { dimension: 'PROJECT', key: 'project:atlas', delta: '223.45' },
        { dimension: 'TEAM', key: 'team:core', delta: '86.68' },
      ],
    })
  })
  it('tolerates unknown future fields', () => {
    const raw = JSON.stringify([{ dimension: 'PROJECT', key: 'p:1', delta: '1.00', share: 0.5, extra: { deep: true } }])
    const result = parseDriversJson(raw)
    expect(result.ok).toBe(true)
    if (result.ok) expect(result.drivers).toEqual([{ dimension: 'PROJECT', key: 'p:1', delta: '1.00' }])
  })
  it('degrades gracefully instead of throwing', () => {
    expect(parseDriversJson(null)).toEqual({ ok: false, reason: 'empty' })
    expect(parseDriversJson('   ')).toEqual({ ok: false, reason: 'empty' })
    expect(parseDriversJson('{oops')).toEqual({ ok: false, reason: 'invalid-json' })
    expect(parseDriversJson('{"a":1}')).toEqual({ ok: false, reason: 'invalid-shape' })
    expect(parseDriversJson(JSON.stringify([{ dimension: 'X' }]))).toEqual({ ok: false, reason: 'invalid-shape' })
    expect(parseDriversJson(JSON.stringify([{ dimension: 'X', key: 'k', delta: 5 }]))).toEqual({ ok: false, reason: 'invalid-shape' })
    expect(parseDriversJson('[]')).toEqual({ ok: true, drivers: [] })
  })
})
