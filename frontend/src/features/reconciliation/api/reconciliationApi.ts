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
  CaseAdjustmentRequest,
  CaseAdjustmentResponse,
  ChargeDispositionRequest,
  ChargeDispositionResponse,
  GatewayResolutionRequest,
  GatewayResolutionResponse,
  LinkCorrectionRequest,
  LinkCorrectionResponse,
  ReconciliationEvidencePage,
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

  async listRunEvidence(runId: string, page = 0, size = 50): Promise<ReconciliationEvidencePage> {
    return (await apiClient.get<ReconciliationEvidencePage>(
      `/reconciliation-runs/${encodeURIComponent(runId)}/evidence`,
      { params: { page, size } },
    )).data
  },

  async listCaseEvidence(caseId: string, page = 0, size = 50): Promise<ReconciliationEvidencePage> {
    return (await apiClient.get<ReconciliationEvidencePage>(
      `/reconciliation-cases/${encodeURIComponent(caseId)}/evidence`,
      { params: { page, size } },
    )).data
  },

  async decideChargeDisposition(caseId: string, body: ChargeDispositionRequest, idempotencyKey: string): Promise<ChargeDispositionResponse> {
    return (await apiClient.post<ChargeDispositionResponse>(
      `/reconciliation-cases/${encodeURIComponent(caseId)}/charge-dispositions`,
      body,
      { headers: { 'Idempotency-Key': idempotencyKey } },
    )).data
  },

  async postCaseAdjustment(caseId: string, body: CaseAdjustmentRequest, idempotencyKey: string): Promise<CaseAdjustmentResponse> {
    return (await apiClient.post<CaseAdjustmentResponse>(
      `/reconciliation-cases/${encodeURIComponent(caseId)}/adjustments`,
      body,
      { headers: { 'Idempotency-Key': idempotencyKey } },
    )).data
  },

  async postGatewayResolution(runId: string, body: GatewayResolutionRequest, idempotencyKey: string): Promise<GatewayResolutionResponse> {
    return (await apiClient.post<GatewayResolutionResponse>(
      `/reconciliation-runs/${encodeURIComponent(runId)}/gateway-resolutions`,
      body,
      { headers: { 'Idempotency-Key': idempotencyKey } },
    )).data
  },

  async linkCorrection(caseId: string, body: LinkCorrectionRequest): Promise<LinkCorrectionResponse> {
    return (await apiClient.post<LinkCorrectionResponse>(
      `/reconciliation-cases/${encodeURIComponent(caseId)}/link-correction`,
      body,
    )).data
  },
}
