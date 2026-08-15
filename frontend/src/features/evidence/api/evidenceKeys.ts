export const evidenceKeys = {
  lists: () => ['evidence', 'list'] as const,
  list: (page: number, size: number) => ['evidence', 'list', page, size] as const,
  detail: (id: string) => ['evidence', 'detail', id] as const,
  imports: (id: string, page: number, size: number) => ['evidence', id, 'imports', page, size] as const,
}
