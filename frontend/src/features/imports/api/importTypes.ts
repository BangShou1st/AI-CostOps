/** Browser-facing Import workflow types; every identifier is a decimal string. */

export type ImportBatchStatus = 'PENDING' | 'PROCESSING' | 'PARSED' | 'FAILED' | 'CANCELED'
export type ImportAttemptStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELED'
export type ImportAttemptTrigger = 'INITIAL' | 'LEASE_RECOVERY' | 'MANUAL_RETRY'
export type IssueSeverity = 'WARN' | 'ERROR'
export type RawRecordNormalizeStatus = 'NORMALIZED' | 'WARN' | 'ERROR'
export type ImportSourceType = 'FILE_EXPORT' | 'USAGE_API_JSON' | 'COSTS_API_JSON'

export interface ImportSummary {
  id: string
  evidence: { id: string; originalFilename: string }
  providerAccount: { id: string; displayName: string }
  expectedProviderCode: string
  sourceType: ImportSourceType | null
  parserVersion: string
  status: ImportBatchStatus | null
  periodStart: string | null
  periodEnd: string | null
  latestAttempt: AttemptSummary | null
  createdByMemberId: string
  createdAt: string
  updatedAt: string
  retryable: boolean
  cancelable: boolean
}

export interface AttemptSummary {
  id: string
  attemptNo: number
  status: ImportAttemptStatus | null
  triggerType: ImportAttemptTrigger | null
  predecessorAttemptId: string | null
  parserVersion: string
  detectedProviderCode: string | null
  schemaFingerprint: string | null
  startedAt: string | null
  finishedAt: string | null
  createdAt: string
  recordsSeen: number
  recordsValid: number
  warningCount: number
  errorCount: number
  errorCode: string | null
  errorSummary: string | null
}

export interface IssueSummary {
  id: string
  rawProviderRecordId: string | null
  severity: IssueSeverity | null
  issueCode: string
  recordLocator: string | null
  fieldName: string | null
  message: string
  rawValueMasked: string | null
  createdAt: string
}

export interface KeySummary {
  keyCount: number
  keys: string[]
  keysTruncated: boolean
}

export interface RawRecordSummary {
  id: string
  recordIndex: number
  recordLocator: string
  providerRecordKey: string | null
  normalizeStatus: RawRecordNormalizeStatus | null
  usageStart: string | null
  usageEnd: string | null
  rawPayloadKeys: KeySummary
  normalizedPayloadKeys: KeySummary
  createdAt: string
}

/** Lazy-loaded raw record detail; payloads are JSON-safe unknown data. */
export interface RawRecordDetail {
  id: string
  recordIndex: number
  recordLocator: string
  providerRecordKey: string | null
  normalizeStatus: RawRecordNormalizeStatus | null
  usageStart: string | null
  usageEnd: string | null
  rawPayload: unknown
  normalizedPayload: unknown | null
  createdAt: string
}

export interface ProviderImportResult {
  evidenceId: string
  importBatchId: string
  latestAttemptId: string
  batchStatus: ImportBatchStatus
  duplicateEvidence: boolean
  duplicateBatch: boolean
}

export interface ImportListParams {
  page: number
  size: number
  status?: ImportBatchStatus
  providerAccountId?: string
}

export interface IssueListParams {
  page: number
  size: number
  severity?: IssueSeverity
  issueCode?: string
}

export interface RawRecordListParams {
  page: number
  size: number
  normalizeStatus?: RawRecordNormalizeStatus
}
