import type { PageResponse } from '../../api/pagination'
import type { BillingPeriodStatus } from '../budgets/api/billingPeriodApi'

/**
 * Billing periods are owned by the budgets API client. Re-export its wire types
 * instead of maintaining a second, drifting copy in the period-close feature.
 */
export type { BillingPeriodResponse, BillingPeriodStatus } from '../budgets/api/billingPeriodApi'

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
