/** M18 frozen Cost Intelligence contract (decimal-string money, ISO-8601 UTC). */

export type IntelligenceRunStatus = 'COMPLETED' | 'FAILED' | 'STALE' | string

export interface IntelligenceSummary {
  runId: number | null
  status: IntelligenceRunStatus
  anomalyCount: number
  forecastCount: number
  openRecommendations: number
}

export interface CostAnomaly {
  id: number
  grainType: string
  grainKey: string
  currency: string
  observedAmount: string
  baselineAmount: string
  deltaAmount: string
  deltaPercent: string
  robustZScore: number
  driversJson: string
}

export interface CostForecast {
  scopeType: string
  scopeKey: string
  currency: string
  projectedAmount: string
  method: string
  historyBucketCount: number
  confidence: string
  observedThrough: string
}

export interface BudgetRisk {
  immediateExposure: string
  projectedPeriodEnd: string
  budgetTotal: string
  currency: string
  risk: 'LOW' | 'WATCH' | 'HIGH' | 'OVER_BUDGET' | string
}

export type RecommendationStatus = 'OPEN' | 'ACKNOWLEDGED' | 'DISMISSED' | 'APPLIED' | 'EXPIRED' | string

export interface SavingRecommendation {
  id: number
  logicalModelId: number
  currentProviderAccountId: number
  candidateProviderAccountId: number
  candidateProviderModelId: number
  currency: string
  currentCost: string
  candidateCost: string
  potentialSaving: string
  potentialSavingPercent: string
  status: RecommendationStatus
  routingPolicyId: number | null
  routingChangeRequired: boolean
  calculatedAt: string
}
