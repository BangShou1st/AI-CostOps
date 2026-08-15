import type { ImportListParams, IssueListParams, RawRecordListParams } from './importTypes'

export const importKeys = {
  lists: () => ['imports', 'list'] as const,
  list: (params: ImportListParams) => ['imports', 'list', params] as const,
  detail: (importId: string) => ['imports', 'detail', importId] as const,
  attempts: (importId: string, page: number, size: number) =>
    ['imports', importId, 'attempts', page, size] as const,
  issues: (importId: string, attemptId: string, params: IssueListParams) =>
    ['imports', importId, 'attempts', attemptId, 'issues', params] as const,
  rawRecords: (importId: string, attemptId: string, params: RawRecordListParams) =>
    ['imports', importId, 'attempts', attemptId, 'raw-records', params] as const,
  rawRecord: (importId: string, attemptId: string, recordId: string) =>
    ['imports', importId, 'attempts', attemptId, 'raw-records', recordId] as const,
}
