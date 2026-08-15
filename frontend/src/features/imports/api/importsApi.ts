import { apiClient } from '../../auth/authApi'
import type { PageResponse } from '../../../api/pagination'
import type {
  AttemptSummary,
  ImportListParams,
  ImportSourceType,
  ImportSummary,
  IssueListParams,
  IssueSummary,
  ProviderImportResult,
  RawRecordDetail,
  RawRecordListParams,
  RawRecordSummary,
} from './importTypes'

export const importsApi = {
  async listImports(params: ImportListParams) {
    return (await apiClient.get<PageResponse<ImportSummary>>('/imports', { params })).data
  },
  async getImport(importId: string) {
    return (await apiClient.get<ImportSummary>(`/imports/${encodeURIComponent(importId)}`)).data
  },
  async listEvidenceImports(evidenceId: string, page: number, size: number) {
    return (await apiClient.get<PageResponse<ImportSummary>>(
      `/evidence/${encodeURIComponent(evidenceId)}/imports`, { params: { page, size } })).data
  },
  async listAttempts(importId: string, page: number, size: number) {
    return (await apiClient.get<PageResponse<AttemptSummary>>(
      `/imports/${encodeURIComponent(importId)}/attempts`, { params: { page, size } })).data
  },
  async listIssues(importId: string, attemptId: string, params: IssueListParams) {
    return (await apiClient.get<PageResponse<IssueSummary>>(
      `/imports/${encodeURIComponent(importId)}/attempts/${encodeURIComponent(attemptId)}/issues`,
      { params })).data
  },
  async listRawRecords(importId: string, attemptId: string, params: RawRecordListParams) {
    return (await apiClient.get<PageResponse<RawRecordSummary>>(
      `/imports/${encodeURIComponent(importId)}/attempts/${encodeURIComponent(attemptId)}/raw-records`,
      { params })).data
  },
  async getRawRecord(importId: string, attemptId: string, recordId: string) {
    return (await apiClient.get<RawRecordDetail>(
      `/imports/${encodeURIComponent(importId)}/attempts/${encodeURIComponent(attemptId)}`
      + `/raw-records/${encodeURIComponent(recordId)}`)).data
  },
  /** One explicit command invocation carries exactly one Idempotency-Key. */
  async retry(importId: string, idempotencyKey: string) {
    return (await apiClient.post<ImportSummary>(`/imports/${encodeURIComponent(importId)}/retry`, undefined, {
      headers: { 'Idempotency-Key': idempotencyKey },
    })).data
  },
  async cancel(importId: string, idempotencyKey: string) {
    return (await apiClient.post<ImportSummary>(`/imports/${encodeURIComponent(importId)}/cancel`, undefined, {
      headers: { 'Idempotency-Key': idempotencyKey },
    })).data
  },
  async uploadProviderImport(input: {
    file: File
    providerAccountId: string
    sourceType: ImportSourceType
  }) {
    const form = new FormData()
    form.append('file', input.file)
    form.append('providerAccountId', input.providerAccountId)
    form.append('sourceType', input.sourceType)
    return (await apiClient.post<ProviderImportResult>('/provider-imports', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })).data
  },
}
