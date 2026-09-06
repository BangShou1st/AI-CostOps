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
  evidenceReference: string | null
  externalAmount: string | null
  internalAmount: string | null
  differenceAmount: string | null
  createdAt: string
}

/** Bounded positive-proof vocabulary for NO_CHARGE_CONFIRMED. */
export const NO_CHARGE_PROOF_CODES = [
  'PROVIDER_PORTAL_CONFIRMED_NO_CHARGE',
  'PROVIDER_SUPPORT_CONFIRMED_NO_CHARGE',
  'EXPLICIT_ZERO_PROVIDER_RECORD',
] as const

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
  /**
   * Statement charge lineage for STATEMENT_ADJUSTMENT_POSTED. When the run
   * already holds exact correlation evidence for the request the server
   * derives the charge and the field may be omitted; a supplied value is only
   * an equality assertion against that binding. Without exact evidence the
   * reviewed charge id is required.
   */
  statementChargeFactId?: string | null
  /** Required for NO_CHARGE_CONFIRMED; auditable positive-proof reference. */
  positiveEvidenceReference?: string | null
  correctionPeriodId?: string | null
  /**
   * Business reason only. The binding classification
   * (EXACT_PROVIDER_REQUEST / MANUAL_BINDING) is server-derived truth and can
   * never be declared by the client.
   */
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

/** Bounded, server-validated evidence list filters (never free-form). */
export interface ReconciliationEvidenceListParams {
  page?: number
  size?: number
  matchKind?: ReconciliationMatchKind
  gatewayRequestId?: string
}

export type ReconciliationRunPage = PageResponse<ReconciliationRunResponse>
export type ReconciliationCasePage = PageResponse<ReconciliationCaseResponse>
export type ReconciliationEvidencePage = PageResponse<ReconciliationEvidenceResponse>
