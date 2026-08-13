export function hasPermission(permissions: readonly string[] | undefined, code: string): boolean {
  return permissions?.includes(code) ?? false
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
