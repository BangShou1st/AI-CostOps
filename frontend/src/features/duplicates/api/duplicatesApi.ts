import { apiClient } from '../../auth/authApi'
import type { PageResponse } from '../../../api/pagination'

/** Duplicate candidate review types; ids are decimal strings. */
export type CandidateStatus =
  | 'OPEN'
  | 'KEPT_CLEAN'
  | 'CONFIRMED_DUPLICATE'
  | 'SUPERSEDED'
export type CandidateType = 'EXACT' | 'OVERLAP'
export type ChargeReviewStatus =
  | 'CLEAN'
  | 'SUSPECTED_DUPLICATE'
  | 'EXCLUDED_DUPLICATE'
  | 'EXCLUDED_NONCOST'

export interface DuplicateChargeRef {
  id: string
  providerCode: string
  chargeCategory: string
  amount: string
  currency: string
  periodStart: string | null
  periodEnd: string | null
  reviewStatus: ChargeReviewStatus
  duplicateOfChargeId: string | null
}

export interface DuplicateCandidate {
  id: string
  candidateType: CandidateType
  fingerprint: string
  algorithmVersion: string
  matchReason: string
  status: CandidateStatus
  chargeFact: DuplicateChargeRef
  matchedChargeFact: DuplicateChargeRef
  createdAt: string
  resolvedAt: string | null
}

export interface DuplicateListParams {
  page: number
  size: number
  status?: CandidateStatus
}

export const duplicatesApi = {
  async listCandidates(params: DuplicateListParams): Promise<PageResponse<DuplicateCandidate>> {
    return (await apiClient.get<PageResponse<DuplicateCandidate>>('/duplicate-candidates', { params })).data
  },
  async keep(candidateId: string, idempotencyKey: string): Promise<DuplicateCandidate> {
    return (await apiClient.post<DuplicateCandidate>(
      `/duplicate-candidates/${encodeURIComponent(candidateId)}/keep`,
      undefined,
      { headers: { 'Idempotency-Key': idempotencyKey } },
    )).data
  },
  async exclude(candidateId: string, excludedChargeFactId: string, idempotencyKey: string): Promise<DuplicateCandidate> {
    return (await apiClient.post<DuplicateCandidate>(
      `/duplicate-candidates/${encodeURIComponent(candidateId)}/exclude`,
      { excludedChargeFactId },
      { headers: { 'Idempotency-Key': idempotencyKey } },
    )).data
  },
}
