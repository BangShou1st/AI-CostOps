import type { CloseRunListParams } from '../types'

export const periodCloseKeys = {
  periods: () => ['period-close', 'periods'] as const,
  readiness: (periodId: string) => ['period-close', 'readiness', periodId] as const,
  closeRuns: (periodId: string, params: CloseRunListParams) => ['period-close', 'runs', periodId, params] as const,
  closeRun: (periodId: string, runId: string) => ['period-close', 'run', periodId, runId] as const,
}
