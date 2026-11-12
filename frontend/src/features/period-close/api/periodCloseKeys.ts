import type { CloseRunListParams } from '../types'

export const periodCloseKeys = {
  // The billing-period list belongs to the budgets client: billingPeriodKeys.list().
  readiness: (periodId: string) => ['period-close', 'readiness', periodId] as const,
  closeRuns: (periodId: string, params: CloseRunListParams) => ['period-close', 'runs', periodId, params] as const,
  closeRun: (periodId: string, runId: string) => ['period-close', 'run', periodId, runId] as const,
}
