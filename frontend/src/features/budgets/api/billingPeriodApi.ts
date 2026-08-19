import { apiClient } from '../../auth/authApi'

export type BillingPeriodStatus = 'OPEN' | 'CLOSING' | 'CLOSED'

export interface BillingPeriodResponse {
  id: string
  periodStart: string
  periodEnd: string
  status: BillingPeriodStatus
  version: number
}

export const billingPeriodApi = {
  async list(): Promise<BillingPeriodResponse[]> {
    return (await apiClient.get<BillingPeriodResponse[]>('/billing-periods')).data
  },
}

export const billingPeriodKeys = {
  list: () => ['billing-period', 'list'] as const,
}
