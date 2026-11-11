import { describe, expect, it } from 'vitest'
import { compareNumericIdDesc, sortByNumericIdDesc } from './numericIdOrder'

describe('numericIdOrder', () => {
  it('sorts numeric string ids newest-first instead of lexicographically', () => {
    const rows = [{ id: '9' }, { id: '10' }, { id: '100' }]

    expect(sortByNumericIdDesc(rows).map(row => row.id)).toEqual(['100', '10', '9'])
    expect([...rows].sort((left, right) => compareNumericIdDesc(left.id, right.id)).map(row => row.id))
      .toEqual(['100', '10', '9'])
  })

  it('keeps equal ids stable and does not mutate the input', () => {
    const rows = [{ id: '7', label: 'first' }, { id: '7', label: 'second' }, { id: '8', label: 'third' }]
    const sorted = sortByNumericIdDesc(rows)

    expect(sorted.map(row => row.label)).toEqual(['third', 'first', 'second'])
    expect(rows.map(row => row.label)).toEqual(['first', 'second', 'third'])
  })

  it('falls back to descending locale order for non-numeric ids', () => {
    expect(compareNumericIdDesc('beta', 'alpha')).toBeLessThan(0)
    expect(sortByNumericIdDesc([{ id: 'alpha' }, { id: 'beta' }]).map(row => row.id))
      .toEqual(['beta', 'alpha'])
  })
})
