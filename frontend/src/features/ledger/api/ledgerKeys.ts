import type { LedgerListParams } from './ledgerApi'

export const ledgerKeys = {
  lists: () => ['ledger', 'list'] as const,
  postings: (params: LedgerListParams) => ['ledger', 'postings', params] as const,
  posting: (id: string) => ['ledger', 'posting', id] as const,
  entries: (params: LedgerListParams) => ['ledger', 'entries', params] as const,
  entry: (id: string) => ['ledger', 'entry', id] as const,
}
