export const authMeKey = ['auth', 'me'] as const

export const settingsKeys = {
  usersAll: () => ['settings', 'users'] as const,
  users: (page: number, size: number) => ['settings', 'users', 'list', page, size] as const,
  user: (id: string) => ['settings', 'users', 'detail', id] as const,
  roles: () => ['settings', 'roles'] as const,
  permissions: () => ['settings', 'permissions'] as const,
  invitations: () => ['settings', 'invitations'] as const,
}
