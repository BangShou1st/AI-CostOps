export const authMeKey = ['auth', 'me'] as const

export const settingsKeys = {
  usersAll: () => ['settings', 'users'] as const,
  users: (page: number, size: number) => ['settings', 'users', 'list', page, size] as const,
  user: (id: string) => ['settings', 'users', 'detail', id] as const,
  roles: () => ['settings', 'roles'] as const,
  permissions: () => ['settings', 'permissions'] as const,
  invitations: () => ['settings', 'invitations'] as const,
  projectsAll: () => ['settings', 'projects'] as const,
  projects: (page: number, size: number) => ['settings', 'projects', 'list', page, size] as const,
  projectMembers: (id: string, page: number, size: number) => ['settings', 'projects', 'members', id, page, size] as const,
  teamsAll: () => ['settings', 'teams'] as const,
  teams: (page: number, size: number) => ['settings', 'teams', 'list', page, size] as const,
  teamMembers: (id: string, page: number, size: number) => ['settings', 'teams', 'members', id, page, size] as const,
}
