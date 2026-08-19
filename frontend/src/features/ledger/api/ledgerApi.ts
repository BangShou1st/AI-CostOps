import { apiClient } from '../../auth/authApi'
import type { PageResponse } from '../../../api/pagination'

export type LedgerSourceType = 'PROVIDER_CHARGE' | 'EXPENSE_CLAIM' | 'CORRECTION'
export type LedgerEntryType = 'COST' | 'CREDIT' | 'ADJUSTMENT' | 'REVERSAL'

export interface LedgerEntryResponse {
  id: string
  postingId: string
  entryIndex: number
  entryType: LedgerEntryType
  amount: string
  currency: string
  targetType: 'PROJECT' | 'COST_CENTER' | 'TEAM'
  targetId: string
  budgetId: string | null
  sourceChargeFactId: string | null
  sourceExpenseClaimId: string | null
  allocationLineId: string | null
  correctionGroupId: string | null
  reversesEntryId: string | null
  createdAt: string
}

export interface LedgerPostingSummaryResponse {
  id: string
  postingKey: string
  sourceType: LedgerSourceType
  sourceId: string
  allocationDecisionId: string | null
  billingPeriodId: string
  status: string
  postedByMemberId: string
  postedAt: string
  createdAt: string
  visibleEntryCount: number
  visibleTotalAmount: string | null
  visibleCurrency: string | null
  visibleTotals: Record<string, string>
  entries: LedgerEntryResponse[]
}

export interface LedgerLineageResponse {
  allocationLineId: string | null
  allocationDecisionId: string | null
  allocationDecisionStatus: string | null
  chargeFactId: string | null
  chargeProviderCode: string | null
  chargeReviewStatus: string | null
  rawProviderRecordId: string | null
  importAttemptId: string | null
  importBatchId: string | null
  providerEvidenceId: string | null
  expenseClaimId: string | null
  expenseStatus: string | null
  expenseEvidenceId: string | null
  correctionGroupId: string | null
  reversesEntryId: string | null
}

export interface LedgerEntryDetailResponse {
  entry: LedgerEntryResponse
  posting: LedgerPostingSummaryResponse
  lineage: LedgerLineageResponse
}

export interface LedgerListParams {
  page: number
  size: number
  billingPeriodId?: string
  sourceType?: LedgerSourceType
  projectId?: string
  costCenterId?: string
  teamId?: string
  sort?: 'postedAt,asc' | 'postedAt,desc'
}

export interface CommitmentLinkRequest {
  allocationLineId: string
  commitmentId: string
}

export interface CorrectionReplacementRequest {
  amount: string
  currency: string
  projectId: string | null
  costCenterId: string | null
  teamId: string | null
}

export interface CorrectionRequest {
  targetEntryId: string
  correctionPeriodId: string
  mode: 'REVERSAL_ONLY' | 'REPLACE'
  reasonCode: string
  reasonText?: string | null
  replacement: CorrectionReplacementRequest | null
}

export interface LedgerCorrectionResponse {
  correctionGroupId: string
  posting: LedgerPostingSummaryResponse
}

export const ledgerApi = {
  async listPostings(params: LedgerListParams): Promise<PageResponse<LedgerPostingSummaryResponse>> {
    return (await apiClient.get<PageResponse<LedgerPostingSummaryResponse>>('/ledger/postings', { params })).data
  },
  async getPosting(id: string): Promise<LedgerPostingSummaryResponse> {
    return (await apiClient.get<LedgerPostingSummaryResponse>(`/ledger/postings/${encodeURIComponent(id)}`)).data
  },
  async listEntries(params: LedgerListParams): Promise<PageResponse<LedgerEntryResponse>> {
    return (await apiClient.get<PageResponse<LedgerEntryResponse>>('/ledger/entries', { params })).data
  },
  async getEntry(id: string): Promise<LedgerEntryDetailResponse> {
    return (await apiClient.get<LedgerEntryDetailResponse>(`/ledger/entries/${encodeURIComponent(id)}`)).data
  },
  async postCharge(chargeFactId: string, commitmentLinks: CommitmentLinkRequest[] = []) {
    return (await apiClient.post<LedgerPostingSummaryResponse>(
      `/costs/charges/${encodeURIComponent(chargeFactId)}/post`,
      { commitmentLinks },
    )).data
  },
  async postExpense(expenseId: string, commitmentLinks: CommitmentLinkRequest[] = []) {
    return (await apiClient.post<LedgerPostingSummaryResponse>(
      `/expenses/${encodeURIComponent(expenseId)}/post`,
      { commitmentLinks },
    )).data
  },
  async correct(request: CorrectionRequest, idempotencyKey: string) {
    return (await apiClient.post<LedgerCorrectionResponse>('/ledger/corrections', request, {
      headers: { 'Idempotency-Key': idempotencyKey },
    })).data
  },
}
