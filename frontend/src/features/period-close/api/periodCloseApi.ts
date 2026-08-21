import type { PageResponse } from '../../../api/pagination'
import { apiClient } from '../../auth/authApi'
import type {
  BillingPeriodResponse,
  CloseReadinessResponse,
  CloseRunListParams,
  CloseRunPage,
  CloseRunResponse,
  ReopenPeriodRequest,
} from '../types'

export { periodCloseKeys } from './periodCloseKeys'

/** Period-close API values mirror docs/02-development/api/openapi.yaml. */
export const periodCloseApi = {
  async listBillingPeriods(): Promise<BillingPeriodResponse[]> {
    return (await apiClient.get<BillingPeriodResponse[]>('/billing-periods')).data
  },

  async getReadiness(periodId: string): Promise<CloseReadinessResponse> {
    return (await apiClient.get<CloseReadinessResponse>(
      `/billing-periods/${encodeURIComponent(periodId)}/close-readiness`,
    )).data
  },

  async listCloseRuns(periodId: string, params: CloseRunListParams): Promise<CloseRunPage> {
    return (await apiClient.get<PageResponse<CloseRunResponse>>(
      `/billing-periods/${encodeURIComponent(periodId)}/close-runs`, { params },
    )).data
  },

  async getCloseRun(periodId: string, runId: string): Promise<CloseRunResponse> {
    return (await apiClient.get<CloseRunResponse>(
      `/billing-periods/${encodeURIComponent(periodId)}/close-runs/${encodeURIComponent(runId)}`,
    )).data
  },

  async close(periodId: string, idempotencyKey: string): Promise<CloseRunResponse> {
    return (await apiClient.post<CloseRunResponse>(
      `/billing-periods/${encodeURIComponent(periodId)}/close`, undefined,
      { headers: { 'Idempotency-Key': idempotencyKey } },
    )).data
  },

  async reopen(periodId: string, body: ReopenPeriodRequest, idempotencyKey: string): Promise<CloseRunResponse> {
    return (await apiClient.post<CloseRunResponse>(
      `/billing-periods/${encodeURIComponent(periodId)}/reopen`, body,
      { headers: { 'Idempotency-Key': idempotencyKey } },
    )).data
  },
}
