export type CoverageScope = 'ORGANIZATION' | 'PROJECT' | 'TEAM' | 'COST_CENTER'
export type CoverageStatus = 'REVIEWED_COMPLETE' | 'REVOKED'
export interface CoverageScopeOption { scope: CoverageScope; scopeId: string; name: string }
export interface CoverageReview {
  status: CoverageStatus
  basis: 'MANUAL_LEDGER_REVIEW'
  reviewNote: string
  updatedAt: string
}
export interface CoverageDay {
  postingDay: string
  observation: 'OBSERVED' | 'REVIEWED_ZERO' | 'UNKNOWN'
  amount: string | null
  reviewValid: boolean
  review: CoverageReview | null
}
export interface CoverageView {
  revision: number
  currency: string
  scope: CoverageScope
  scopeId: number
  dateBasis: 'UTC_POSTING_DAY'
  days: CoverageDay[]
}
export interface CoverageReviewRequest {
  currency: string
  scope: CoverageScope
  scopeId: string
  postingDay: string
  status: CoverageStatus
  reviewNote: string
  expectedRevision: number
}
