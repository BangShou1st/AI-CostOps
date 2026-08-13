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

export const SETTINGS_NAV: readonly SettingsNavEntry[] = [
  { path: '/settings/users', label: 'Users', readPermission: 'USER_READ' },
  { path: '/settings/roles', label: 'Roles', readPermission: 'ROLE_READ' },
  { path: '/settings/projects', label: 'Projects', readPermission: 'PROJECT_READ' },
  { path: '/settings/teams', label: 'Teams', readPermission: 'TEAM_READ' },
  { path: '/settings/cost-centers', label: 'Cost centers', readPermission: 'COST_CENTER_READ' },
  { path: '/settings/provider-accounts', label: 'Provider accounts', readPermission: 'PROVIDER_ACCOUNT_READ' },
]

export function visibleSettingsNav(permissions: readonly string[] | undefined): readonly SettingsNavEntry[] {
  return SETTINGS_NAV.filter((entry) => hasPermission(permissions, entry.readPermission))
}
