import type { CostListParams } from './costsApi'

export const costKeys = {
  lists: () => ['costs', 'list'] as const,
  list: (params: CostListParams) => ['costs', 'list', params] as const,
  detail: (chargeId: string) => ['costs', 'detail', chargeId] as const,
}
