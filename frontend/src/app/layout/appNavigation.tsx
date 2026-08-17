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
]

export function visibleBusinessNav(permissions: readonly string[] | undefined): readonly AppNavEntry[] {
  return BUSINESS_NAV.filter((entry) => permissions?.includes(entry.readPermission) ?? false)
}
