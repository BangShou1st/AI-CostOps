import { apiClient } from '../../auth/authApi'
import type { PageResponse } from '../../../api/pagination'

/** Browser-facing canonical charge types; ids and money are decimal strings. */
export type ChargeReviewStatus =
  | 'CLEAN'
  | 'SUSPECTED_DUPLICATE'
  | 'EXCLUDED_DUPLICATE'
  | 'EXCLUDED_NONCOST'

export interface ChargeCostSummary {
  id: string
  providerCode: string
  chargeCategory: string
  amount: string
  currency: string
  periodStart: string | null
  periodEnd: string | null
  reviewStatus: ChargeReviewStatus
  currentAllocationDecisionId: string | null
}

export interface ChargeCostDetail extends ChargeCostSummary {
  duplicateOfChargeId: string | null
  confirmedImport: boolean
}

export interface CostListParams {
  page: number
  size: number
  reviewStatus?: ChargeReviewStatus
}

export const costsApi = {
  async listCharges(params: CostListParams): Promise<PageResponse<ChargeCostSummary>> {
    return (await apiClient.get<PageResponse<ChargeCostSummary>>('/costs/charges', { params })).data
  },
  async getCharge(chargeId: string): Promise<ChargeCostDetail> {
    return (await apiClient.get<ChargeCostDetail>(`/costs/charges/${encodeURIComponent(chargeId)}`)).data
  },
}
