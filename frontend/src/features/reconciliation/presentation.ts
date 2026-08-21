import type { ReconciliationCaseStatus, ReconciliationRunStatus } from './types'

const RUN_STATUS_LABELS: Record<ReconciliationRunStatus, string> = {
  CREATED: '已创建',
  RUNNING: '运行中',
  COMPLETED: '已完成',
  FAILED: '处理失败',
}

const CASE_STATUS_LABELS: Record<ReconciliationCaseStatus, string> = {
  OPEN: '待处理',
  INVESTIGATING: '调查中',
  RESOLVED: '已解决',
}

const CASE_TYPE_LABELS: Record<string, string> = {
  MISSING_INTERNAL: '缺少内部记录',
  MISSING_EXTERNAL: '缺少外部记录',
  AMOUNT_MISMATCH: '金额不一致',
}

export function formatReconciliationRunStatus(status: string): string {
  return RUN_STATUS_LABELS[status as ReconciliationRunStatus] ?? '未知运行状态'
}

export function formatReconciliationCaseStatus(status: string): string {
  return CASE_STATUS_LABELS[status as ReconciliationCaseStatus] ?? '未知处理状态'
}

export function formatReconciliationCaseType(caseType: string): string {
  return CASE_TYPE_LABELS[caseType] ?? '其他对账差异'
}

export function reconciliationRunTagColor(status: string): string {
  if (status === 'COMPLETED') return 'success'
  if (status === 'FAILED') return 'error'
  if (status === 'RUNNING') return 'processing'
  return 'default'
}

export function reconciliationCaseTagColor(status: string): string {
  if (status === 'RESOLVED') return 'success'
  if (status === 'INVESTIGATING') return 'processing'
  return 'warning'
}

export function createIdempotencyKey(): string {
  return typeof crypto.randomUUID === 'function' ? crypto.randomUUID() : `${Date.now()}-${Math.random()}`
}
