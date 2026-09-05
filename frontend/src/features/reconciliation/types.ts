import type { PageResponse } from '../../api/pagination'

export type ReconciliationRunStatus = 'CREATED' | 'RUNNING' | 'COMPLETED' | 'FAILED'
export type ReconciliationCaseStatus = 'OPEN' | 'INVESTIGATING' | 'RESOLVED'

/** Canonical summary emitted by ReconciliationRunService. */
export interface ReconciliationSummary {
  totalKeys: number
  matchedCount: number
  discrepancyCount: number
  exactEvidenceCount: number
  unresolvedGatewayCount: number
}

export type ReconciliationMatchKind =
  | 'EXACT_PROVIDER_REQUEST'
  | 'AGGREGATE_SCOPE'
  | 'GATEWAY_UNRESOLVED'
  | 'MANUAL_BINDING'
  | 'RESOLUTION_ACTION'

export type ReconciliationDifferenceKind =
  | 'PRICING_DRIFT'
  | 'DISCOUNT'
  | 'ROUNDING'
  | 'PROVIDER_CORRECTION'
  | 'LATE_CHARGE'
  | 'BILLING_PERIOD_MISMATCH'
  | 'MISSING_GATEWAY_USAGE'
  | 'UNKNOWN_PROVIDER_CHARGE'
  | 'DUPLICATE_EXTERNAL_CHARGE'
  | 'UNCLASSIFIED'

export interface ReconciliationEvidenceResponse {
  id: string
  reconciliationRunId: string
  reconciliationCaseId: string | null
  evidenceKey: string
  providerAccountId: string
  currency: string
  matchKind: ReconciliationMatchKind
  differenceKind: ReconciliationDifferenceKind | null
  chargeFactId: string | null
  gatewayRequestId: string | null
  gatewayRouteAttemptId: string | null
  gatewayUsageFactId: string | null
  gatewaySettlementId: string | null
  correctionGroupId: string | null
  reconciliationAdjustmentId: string | null
  gatewayFinancialResolutionId: string | null
  ledgerPostingId: string | null
  providerRequestId: string | null
  externalAmount: string | null
  internalAmount: string | null
  differenceAmount: string | null
  createdAt: string
}

export interface ChargeDispositionRequest {
  chargeFactId: string
  disposition: 'RECONCILIATION_EVIDENCE' | 'DIRECT_PROVIDER_CHARGE'
  reasonCode: string
  reasonNote: string
}

export interface ChargeDispositionResponse {
  id: string
  caseId: string
  chargeFactId: string
  disposition: string
  decisionSource: string
}

export interface CaseAdjustmentLineRequest {
  lineIndex: number
  scopeType: 'PROJECT' | 'COST_CENTER' | 'TEAM'
  scopeId: string
  amount: string
}

export interface CaseAdjustmentRequest {
  amount: string
  adjustmentPeriodId: string
  lines: CaseAdjustmentLineRequest[]
  reasonCode: string
  reasonNote: string
}

export interface CaseAdjustmentResponse {
  id: string
  caseId: string | null
  runId: string
  adjustmentScope: string
  amount: string
  currency: string
  adjustmentPeriodId: string | null
}

export interface GatewayResolutionRequest {
  caseId?: string | null
  requestId: string
  resolutionType: 'STATEMENT_ADJUSTMENT_POSTED' | 'NO_CHARGE_CONFIRMED'
  adjustmentAmount?: string | null
  correctionPeriodId?: string | null
  commitmentId?: string | null
  reasonCode: string
  reasonNote: string
}

export interface GatewayResolutionResponse {
  id: string
  runId: string
  caseId: string | null
  requestId: string
  resolutionType: string
  reservationOutcome: string | null
  adjustmentId: string | null
}

export interface LinkCorrectionRequest {
  correctionGroupId: string
}

export interface LinkCorrectionResponse {
  caseId: string
  correctionGroupId: string
}

export interface ReconciliationRunResponse {
  id: string
  billingPeriodId: string
  status: ReconciliationRunStatus
  algorithmVersion: string
  toleranceAmount: string
  basisHash: string | null
  summary: ReconciliationSummary
  createdByMemberId: string
  startedAt: string
  finishedAt: string | null
  errorCode: string | null
  errorSummary: string | null
  createdAt: string
  updatedAt: string
}

export interface ReconciliationCaseResponse {
  id: string
  reconciliationRunId: string
  providerAccountId: string
  currency: string
  caseType: string
  externalAmount: string | null
  internalAmount: string | null
  differenceAmount: string
  externalRowCount: number
  internalRowCount: number
  status: ReconciliationCaseStatus
  reasonCode: string | null
  resolutionNote: string | null
  resolvedByMemberId: string | null
  resolvedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface ReconciliationRunRequest {
  billingPeriodId: string
}

export interface ResolveCaseRequest {
  reasonCode: string
  resolutionNote: string
}

export interface ReconciliationRunListParams {
  billingPeriodId: string
  page: number
  size: number
}

export interface ReconciliationCaseListParams {
  runId: string
  page: number
  size: number
  status?: ReconciliationCaseStatus
}

export type ReconciliationRunPage = PageResponse<ReconciliationRunResponse>
export type ReconciliationCasePage = PageResponse<ReconciliationCaseResponse>
export type ReconciliationEvidencePage = PageResponse<ReconciliationEvidenceResponse>
