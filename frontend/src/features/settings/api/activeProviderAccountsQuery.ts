import { queryOptions } from '@tanstack/react-query'
import { settingsApi } from './settingsApi'
import { settingsKeys } from './settingsKeys'
import type { ProviderAccount } from './settingsTypes'

const PAGE_SIZE = 200

export const activeProviderAccountsQuery = queryOptions({
  queryKey: [...settingsKeys.providerAccountsAll(), 'active-directory'],
  queryFn: async ({ signal }) => {
    const accounts: ProviderAccount[] = []
    let page = 0
    let totalPages: number
    do {
      const result = await settingsApi.listProviderAccounts(page, PAGE_SIZE, 'ACTIVE', signal)
      accounts.push(...result.items)
      totalPages = result.totalPages
      page += 1
    } while (page < totalPages)
    return accounts
  },
})
