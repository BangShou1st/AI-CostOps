import { describe, expect, it } from 'vitest'
import { NAV_ICONS } from './AuthenticatedLayout'
import { BUSINESS_NAV, visibleBusinessNav } from './appNavigation'

describe('business navigation presentation', () => {
  it('has a non-empty icon for every business navigation item', () => {
    expect(BUSINESS_NAV.every((entry) => Boolean(NAV_ICONS[entry.path]))).toBe(true)
  })
})

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
describe('visibleBusinessNav budget entries', () => {
  it('shows budgets only to users with BUDGET_READ', () => {
    expect(visibleBusinessNav(['BUDGET_READ'])).toContainEqual(
      { path: '/budgets', label: '预算', readPermission: 'BUDGET_READ' },
    )
  })

  it('never shows budgets to manage/approve holders without BUDGET_READ', () => {
    const nav = visibleBusinessNav(['BUDGET_MANAGE', 'COMMITMENT_APPROVE', 'COMMITMENT_RELEASE'])
    expect(nav).not.toContainEqual(
      { path: '/budgets', label: '预算', readPermission: 'BUDGET_READ' },
    )
    expect(visibleBusinessNav([])).not.toContainEqual(
      { path: '/budgets', label: '预算', readPermission: 'BUDGET_READ' },
    )
  })
})
