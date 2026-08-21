import type { PageResponse } from '../../api/pagination'

export type BillingPeriodStatus = 'OPEN' | 'CLOSING' | 'CLOSED'
export type CloseBlockerCode =
  | 'OPEN_IMPORTS'
  | 'UNRESOLVED_DUPLICATES'
  | 'UNALLOCATED_CHARGES'
  | 'UNPOSTED_APPROVED_EXPENSES'
  | 'OPEN_MATERIAL_RECONCILIATION'
  | 'PENDING_CORRECTIONS'
  | 'LEDGER_INTEGRITY'
export type CloseCheckResult = 'PASS' | 'FAIL' | 'ERROR'
export type CloseRunStatus = 'CHECKING' | 'BLOCKED' | 'CLOSED' | 'FAILED'

export interface BillingPeriodResponse {
  id: string
  periodStart: string
  periodEnd: string
  status: BillingPeriodStatus
  version: number
}

export interface CloseCheckResponse {
  blockerCode: CloseBlockerCode
  result: CloseCheckResult
  itemCount: number
  summary: Record<string, unknown>
  evaluatedAt: string | null
}

export interface CloseReadinessResponse {
  billingPeriodId: string
  periodStatus: BillingPeriodStatus
  closeGeneration: string
  ready: boolean
  preview: true
  checks: CloseCheckResponse[]
}

export interface CloseRunResponse {
  billingPeriodId: string
  periodStatus: BillingPeriodStatus
  closeGeneration: string
  closingStartedAt: string | null
  closedAt: string | null
  reopenedAt: string | null
  runId: string
  runStatus: CloseRunStatus
  attemptNo: number
  reconciliationRunId: string | null
  startedByMemberId: string
  startedAt: string
  finishedAt: string | null
  errorCode: string | null
  errorSummary: string | null
  checks: CloseCheckResponse[]
}

export interface ReopenPeriodRequest {
  reasonCode: string
  reasonNote: string
}

export interface CloseRunListParams {
  page: number
  size: number
}

export type CloseRunPage = PageResponse<CloseRunResponse>
