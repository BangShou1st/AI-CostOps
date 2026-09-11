import type { BudgetRisk, CostAnomaly, CostForecast, IntelligenceSummary, SavingRecommendation } from '../api/intelligenceTypes'

/**
 * Deterministic M18-schema fixture for DEV visual benchmark + unit tests only.
 * NEVER imported by production routes. All money is decimal-string.
 */
export interface OverviewFigureBucket {
  day: string
  actual: string | null
  forecast: string | null
}

export const overviewFixture = {
  currency: 'USD',
  generatedAt: '2026-09-11T02:00:00Z',
  periodLabel: 'September 2026',
  summary: {
    runId: 42, status: 'COMPLETED', anomalyCount: 2, forecastCount: 1, openRecommendations: 2,
  } satisfies IntelligenceSummary,
  actualTotal: '12430.22',
  forecastTotal: '16820.45',
  budgetTotal: '20000.00',
  riskThreshold: '18000.00',
  figure: [
    { day: '2026-09-01', actual: '880.10', forecast: null },
    { day: '2026-09-02', actual: '912.44', forecast: null },
    { day: '2026-09-03', actual: '874.02', forecast: null },
    { day: '2026-09-04', actual: '945.88', forecast: null },
    { day: '2026-09-05', actual: '901.15', forecast: null },
    { day: '2026-09-06', actual: '1180.60', forecast: null },
    { day: '2026-09-07', actual: '1215.33', forecast: null },
    { day: '2026-09-08', actual: '960.20', forecast: null },
    { day: '2026-09-09', actual: '1010.75', forecast: null },
    { day: '2026-09-10', actual: '1044.90', forecast: null },
    { day: '2026-09-11', actual: '504.85', forecast: '520.10' },
    { day: '2026-09-12', actual: null, forecast: '1018.40' },
    { day: '2026-09-13', actual: null, forecast: '1022.75' },
    { day: '2026-09-14', actual: null, forecast: '1031.20' },
  ] satisfies OverviewFigureBucket[],
  anomalies: [
    {
      id: 7, grainType: 'LOGICAL_MODEL', grainKey: 'gpt-5-class', currency: 'USD',
      observedAmount: '1215.33', baselineAmount: '905.20', deltaAmount: '310.13',
      deltaPercent: '34.3', robustZScore: 4.1,
      driversJson: JSON.stringify([{ dimension: 'PROJECT', key: 'project:atlas', delta: '223.45' }, { dimension: 'TEAM', key: 'team:core', delta: '86.68' }]),
    },
    {
      id: 8, grainType: 'PROVIDER', grainKey: 'opencode-zen', currency: 'USD',
      observedAmount: '1180.60', baselineAmount: '890.00', deltaAmount: '290.60',
      deltaPercent: '32.7', robustZScore: 3.6,
      driversJson: JSON.stringify([{ dimension: 'PROJECT', key: 'project:mercury', delta: '201.10' }, { dimension: 'TEAM', key: 'team:core', delta: '89.50' }]),
    },
  ] satisfies CostAnomaly[],
  forecast: {
    scopeType: 'ORGANIZATION', scopeKey: 'org:1', currency: 'USD', projectedAmount: '16820.45',
    method: 'DAMPED_HOLT', historyBucketCount: 29, confidence: 'MEDIUM', observedThrough: '2026-09-10',
  } satisfies CostForecast,
  budgetRisk: {
    immediateExposure: '12430.22', projectedPeriodEnd: '16820.45', budgetTotal: '20000.00',
    currency: 'USD', risk: 'WATCH',
  } satisfies BudgetRisk,
  recommendations: [
    {
      id: 3, logicalModelId: 11, currentProviderAccountId: 5, candidateProviderAccountId: 9,
      candidateProviderModelId: 77, currency: 'USD', currentCost: '4210.00', candidateCost: '2890.40',
      potentialSaving: '1319.60', potentialSavingPercent: '31.3', status: 'OPEN', routingPolicyId: null,
      routingChangeRequired: true, calculatedAt: '2026-09-11T02:00:00Z',
    },
    {
      id: 4, logicalModelId: 12, currentProviderAccountId: 5, candidateProviderAccountId: 5,
      candidateProviderModelId: 78, currency: 'USD', currentCost: '1980.00', candidateCost: '1640.25',
      potentialSaving: '339.75', potentialSavingPercent: '17.2', status: 'ACKNOWLEDGED', routingPolicyId: 2,
      routingChangeRequired: false, calculatedAt: '2026-09-10T02:00:00Z',
    },
  ] satisfies SavingRecommendation[],
}

export type OverviewFixture = typeof overviewFixture
