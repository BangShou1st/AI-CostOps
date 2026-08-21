import type {
  ImportAttemptStatus,
  ImportAttemptTrigger,
  ImportBatchStatus,
  ImportSourceType,
  IssueSeverity,
  RawRecordNormalizeStatus,
} from './api/importTypes'

const IMPORT_STATUS_LABELS: Record<ImportBatchStatus, string> = {
  PENDING: '等待处理',
  PROCESSING: '处理中',
  PARSED: '已解析',
  READY_FOR_REVIEW: '待确认',
  CONFIRMED: '已确认',
  FAILED: '处理失败',
  CANCELED: '已取消',
}

const ATTEMPT_STATUS_LABELS: Record<ImportAttemptStatus, string> = {
  QUEUED: '排队中',
  RUNNING: '执行中',
  SUCCEEDED: '处理成功',
  FAILED: '处理失败',
  CANCELED: '已取消',
}

const ATTEMPT_TRIGGER_LABELS: Record<ImportAttemptTrigger, string> = {
  INITIAL: '首次处理',
  LEASE_RECOVERY: '租约恢复',
  MANUAL_RETRY: '手动重试',
}

const SOURCE_TYPE_LABELS: Record<ImportSourceType, string> = {
  FILE_EXPORT: '文件导出',
  USAGE_API_JSON: '用量 API',
  COSTS_API_JSON: '费用 API',
}

const ISSUE_SEVERITY_LABELS: Record<IssueSeverity, string> = {
  WARN: '警告',
  ERROR: '错误',
}

const RAW_STATUS_LABELS: Record<RawRecordNormalizeStatus, string> = {
  NORMALIZED: '已归一化',
  WARN: '归一化警告',
  ERROR: '归一化失败',
}

export function formatImportStatus(status: string | null | undefined): string {
  return status ? IMPORT_STATUS_LABELS[status as ImportBatchStatus] ?? '未知导入状态' : '—'
}

export function formatImportAttemptStatus(status: string | null | undefined): string {
  return status ? ATTEMPT_STATUS_LABELS[status as ImportAttemptStatus] ?? '未知尝试状态' : '—'
}

export function formatImportAttemptTrigger(trigger: string | null | undefined): string {
  return trigger ? ATTEMPT_TRIGGER_LABELS[trigger as ImportAttemptTrigger] ?? '其他触发方式' : '—'
}

export function formatImportSourceType(sourceType: string | null | undefined): string {
  return sourceType ? SOURCE_TYPE_LABELS[sourceType as ImportSourceType] ?? '其他来源' : '—'
}

export function formatIssueSeverity(severity: string | null | undefined): string {
  return severity ? ISSUE_SEVERITY_LABELS[severity as IssueSeverity] ?? '其他级别' : '—'
}

export function formatRawRecordNormalizeStatus(status: string | null | undefined): string {
  return status ? RAW_STATUS_LABELS[status as RawRecordNormalizeStatus] ?? '未知归一化状态' : '—'
}

export function createImportIdempotencyKey(): string {
  return typeof crypto.randomUUID === 'function' ? crypto.randomUUID() : `${Date.now()}-${Math.random()}`
}
