export interface AppNavEntry {
  path: string
  label: string
  readPermission: string
}

/** Permission-aware business navigation entries. */
export const BUSINESS_NAV: readonly AppNavEntry[] = [
  { path: '/workbench', label: '工作台', readPermission: 'WORKBENCH_SECTION' },
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

export const FINANCE_NAV_PATHS = ['/workbench', '/budgets', '/ledger', '/reconciliation', '/period-close'] as const

/**
 * Any ORG section grant admits the workbench itself; the page then renders
 * only the cards matching the caller's grants.
 */
export const WORKBENCH_SECTION_PERMISSIONS: readonly string[] = [
  'PERIOD_READ',
  'COST_READ',
  'BUDGET_READ',
  'ALLOCATION_READ',
  'DUPLICATE_REVIEW',
  'EXPENSE_REVIEW',
  'RECONCILIATION_READ',
]

export function hasWorkbenchAccess(permissions: readonly string[] | undefined): boolean {
  return permissions?.some((permission) => WORKBENCH_SECTION_PERMISSIONS.includes(permission)) ?? false
}

export function visibleBusinessNav(permissions: readonly string[] | undefined): readonly AppNavEntry[] {
  return BUSINESS_NAV.filter((entry) => entry.path === '/workbench'
    ? hasWorkbenchAccess(permissions)
    : permissions?.includes(entry.readPermission) ?? false)
}
