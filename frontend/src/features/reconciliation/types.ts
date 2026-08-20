import type { PageResponse } from '../../api/pagination'

export type ReconciliationRunStatus = 'CREATED' | 'RUNNING' | 'COMPLETED' | 'FAILED'
export type ReconciliationCaseStatus = 'OPEN' | 'INVESTIGATING' | 'RESOLVED'

export type ReconciliationSummary = Record<string, unknown>

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
