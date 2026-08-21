import type { PageResponse } from '../../../api/pagination'
import { apiClient } from '../../auth/authApi'
import type {
  ReconciliationCaseListParams,
  ReconciliationCasePage,
  ReconciliationCaseResponse,
  ReconciliationRunListParams,
  ReconciliationRunPage,
  ReconciliationRunRequest,
  ReconciliationRunResponse,
  ResolveCaseRequest,
} from '../types'

/** Reconciliation API values mirror docs/02-development/api/openapi.yaml. */
export const reconciliationApi = {
  async listRuns(params: ReconciliationRunListParams): Promise<ReconciliationRunPage> {
    return (await apiClient.get<PageResponse<ReconciliationRunResponse>>('/reconciliation-runs', { params })).data
  },

  async createRun(body: ReconciliationRunRequest, idempotencyKey: string): Promise<ReconciliationRunResponse> {
    return (await apiClient.post<ReconciliationRunResponse>('/reconciliation-runs', body, {
      headers: { 'Idempotency-Key': idempotencyKey },
    })).data
  },

  async getRun(runId: string): Promise<ReconciliationRunResponse> {
    return (await apiClient.get<ReconciliationRunResponse>(`/reconciliation-runs/${encodeURIComponent(runId)}`)).data
  },

  async listCases(params: ReconciliationCaseListParams): Promise<ReconciliationCasePage> {
    return (await apiClient.get<PageResponse<ReconciliationCaseResponse>>('/reconciliation-cases', { params })).data
  },

  async getCase(caseId: string): Promise<ReconciliationCaseResponse> {
    return (await apiClient.get<ReconciliationCaseResponse>(`/reconciliation-cases/${encodeURIComponent(caseId)}`)).data
  },

  async investigateCase(caseId: string, idempotencyKey: string): Promise<ReconciliationCaseResponse> {
    return (await apiClient.post<ReconciliationCaseResponse>(
      `/reconciliation-cases/${encodeURIComponent(caseId)}/investigate`,
      undefined,
      { headers: { 'Idempotency-Key': idempotencyKey } },
    )).data
  },

  async returnCaseToOpen(caseId: string, idempotencyKey: string): Promise<ReconciliationCaseResponse> {
    return (await apiClient.post<ReconciliationCaseResponse>(
      `/reconciliation-cases/${encodeURIComponent(caseId)}/return-open`,
      undefined,
      { headers: { 'Idempotency-Key': idempotencyKey } },
    )).data
  },

  async resolveCase(caseId: string, body: ResolveCaseRequest, idempotencyKey: string): Promise<ReconciliationCaseResponse> {
    return (await apiClient.post<ReconciliationCaseResponse>(
      `/reconciliation-cases/${encodeURIComponent(caseId)}/resolve`,
      body,
      { headers: { 'Idempotency-Key': idempotencyKey } },
    )).data
  },
}
