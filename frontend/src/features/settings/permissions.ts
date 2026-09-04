import type { ScopeType } from './api/settingsTypes'

export function hasPermission(permissions: readonly string[] | undefined, code: string): boolean {
  return permissions?.includes(code) ?? false
}

/** Frozen M1 role scope matrix, presented read-only for diagnostics. */
export const ROLE_SCOPE_APPLICABILITY: Record<string, readonly ScopeType[]> = {
  EMPLOYEE: ['ORG'],
  PROJECT_OWNER: ['PROJECT'],
  FINANCE_REVIEWER: ['ORG', 'COST_CENTER'],
  FINANCE_ADMIN: ['ORG'],
  SYSTEM_ADMIN: ['ORG', 'PROJECT', 'TEAM', 'COST_CENTER'],
}

export interface SettingsNavEntry {
  path: string
  label: string
  readPermission: string
}

/** Presentation labels are Simplified Chinese; permission codes stay English. */
export const SETTINGS_NAV: readonly SettingsNavEntry[] = [
  { path: '/settings/users', label: '用户管理', readPermission: 'USER_READ' },
  { path: '/settings/roles', label: '角色与权限', readPermission: 'ROLE_READ' },
  { path: '/settings/projects', label: '项目管理', readPermission: 'PROJECT_READ' },
  { path: '/settings/teams', label: '团队管理', readPermission: 'TEAM_READ' },
  { path: '/settings/cost-centers', label: '成本中心', readPermission: 'COST_CENTER_READ' },
  { path: '/settings/provider-accounts', label: '云账号', readPermission: 'PROVIDER_ACCOUNT_READ' },
  { path: '/settings/routing-policies', label: '路由策略', readPermission: 'PROVIDER_ACCOUNT_READ' },
]

export function visibleSettingsNav(permissions: readonly string[] | undefined): readonly SettingsNavEntry[] {
  return SETTINGS_NAV.filter((entry) => hasPermission(permissions, entry.readPermission))
}
