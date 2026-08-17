import type { DuplicateListParams } from './duplicatesApi'

export const duplicateKeys = {
  lists: () => ['duplicates', 'list'] as const,
  list: (params: DuplicateListParams) => ['duplicates', 'list', params] as const,
  detail: (candidateId: string) => ['duplicates', 'detail', candidateId] as const,
}
