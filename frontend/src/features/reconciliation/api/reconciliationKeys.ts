import type { ReconciliationCaseListParams, ReconciliationRunListParams } from '../types'

export const reconciliationKeys = {
  lists: () => ['reconciliation', 'list'] as const,
  runs: (params: ReconciliationRunListParams) => ['reconciliation', 'runs', params] as const,
  run: (runId: string) => ['reconciliation', 'run', runId] as const,
  cases: (params: ReconciliationCaseListParams) => ['reconciliation', 'cases', params] as const,
  case: (caseId: string) => ['reconciliation', 'case', caseId] as const,
  runEvidence: (runId: string) => ['reconciliation', 'run', runId, 'evidence'] as const,
  caseEvidence: (caseId: string) => ['reconciliation', 'case', caseId, 'evidence'] as const,
}
