import { describe, expect, it } from 'vitest'
import { visibleBusinessNav } from './appNavigation'

describe('visibleBusinessNav expense entries', () => {
  it('shows my expenses only to users with EXPENSE_READ_OWN', () => {
    expect(visibleBusinessNav(['EXPENSE_READ_OWN'])).toEqual([
      { path: '/expenses', label: '我的报销', readPermission: 'EXPENSE_READ_OWN' },
    ])
    expect(visibleBusinessNav([])).not.toContainEqual(
      { path: '/expenses', label: '我的报销', readPermission: 'EXPENSE_READ_OWN' },
    )
  })

  it('shows expense review only to users with EXPENSE_REVIEW', () => {
    expect(visibleBusinessNav(['EXPENSE_REVIEW'])).toEqual([
      { path: '/expense-reviews', label: '报销审核', readPermission: 'EXPENSE_REVIEW' },
    ])
    expect(visibleBusinessNav([])).not.toContainEqual(
      { path: '/expense-reviews', label: '报销审核', readPermission: 'EXPENSE_REVIEW' },
    )
  })
})
