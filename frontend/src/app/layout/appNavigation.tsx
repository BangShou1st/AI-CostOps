export interface AppNavEntry {
  path: string
  label: string
  readPermission: string
}

/** Permission-aware business navigation entries. */
export const BUSINESS_NAV: readonly AppNavEntry[] = [
  { path: '/evidence', label: '证据', readPermission: 'EVIDENCE_READ' },
  { path: '/imports', label: '导入', readPermission: 'IMPORT_READ' },
  { path: '/costs', label: '成本', readPermission: 'COST_READ' },
  { path: '/allocation-rules', label: '分摊规则', readPermission: 'ALLOCATION_RULE_MANAGE' },
  { path: '/expenses', label: '我的报销', readPermission: 'EXPENSE_READ_OWN' },
  { path: '/budgets', label: '预算', readPermission: 'BUDGET_READ' },
  { path: '/expense-reviews', label: '报销审核', readPermission: 'EXPENSE_REVIEW' },
  { path: '/ledger', label: '账本', readPermission: 'LEDGER_READ' },
  { path: '/reconciliation', label: '对账', readPermission: 'RECONCILIATION_READ' },
  { path: '/period-close', label: '期间结账', readPermission: 'PERIOD_READ' },
]

export const FINANCE_NAV_PATHS = ['/budgets', '/ledger', '/reconciliation', '/period-close'] as const

export function visibleBusinessNav(permissions: readonly string[] | undefined): readonly AppNavEntry[] {
  return BUSINESS_NAV.filter((entry) => permissions?.includes(entry.readPermission) ?? false)
}
