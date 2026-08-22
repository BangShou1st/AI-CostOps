import { apiClient } from '../../auth/authApi'
import type { WorkbenchResponse } from './workbenchTypes'

export const workbenchApi = {
  async get(billingPeriodId?: string): Promise<WorkbenchResponse> {
    const params = billingPeriodId ? { billingPeriodId } : undefined
    return (await apiClient.get<WorkbenchResponse>('/workbench', { params })).data
  },
}
