export const providerHubKeys = {
  all: ['provider-hub'] as const,
  templates: () => [...providerHubKeys.all, 'templates'] as const,
  connections: (page: number, size: number) => [...providerHubKeys.all, 'connections', page, size] as const,
  connection: (id: number | null) => [...providerHubKeys.all, 'connection', id] as const,
  revisions: (id: number | null) => [...providerHubKeys.all, 'revisions', id] as const,
  credentials: (id: number | null) => [...providerHubKeys.all, 'credentials', id] as const,
  models: (id: number | null) => [...providerHubKeys.all, 'models', id] as const,
}
