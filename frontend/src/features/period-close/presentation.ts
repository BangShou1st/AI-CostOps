import type { BillingPeriodStatus, CloseBlockerCode, CloseCheckResult, CloseRunStatus } from './types'

const PERIOD_STATUS_LABELS: Record<BillingPeriodStatus, string> = {
  OPEN: '开放',
  CLOSING: '关闭检查中',
  CLOSED: '已关闭',
}

const CLOSE_RUN_STATUS_LABELS: Record<CloseRunStatus, string> = {
  CHECKING: '检查中',
  BLOCKED: '未通过',
  CLOSED: '已关闭',
  FAILED: '处理失败',
}

const CLOSE_RESULT_LABELS: Record<CloseCheckResult, string> = {
  PASS: '通过',
  FAIL: '未通过',
  ERROR: '校验异常',
}

const BLOCKER_LABELS: Record<CloseBlockerCode, string> = {
  OPEN_IMPORTS: '未完成的导入任务',
  UNRESOLVED_DUPLICATES: '未解决的重复记录',
  UNALLOCATED_CHARGES: '未完成分摊的费用',
  UNPOSTED_APPROVED_EXPENSES: '已批准但未入账的报销',
  OPEN_MATERIAL_RECONCILIATION: '未解决的重要对账差异',
  PENDING_CORRECTIONS: '待处理的账务更正',
  LEDGER_INTEGRITY: '账本一致性',
}

export const CLOSE_BLOCKER_ORDER: readonly CloseBlockerCode[] = [
  'OPEN_IMPORTS',
  'UNRESOLVED_DUPLICATES',
  'UNALLOCATED_CHARGES',
  'UNPOSTED_APPROVED_EXPENSES',
  'OPEN_MATERIAL_RECONCILIATION',
  'PENDING_CORRECTIONS',
  'LEDGER_INTEGRITY',
]

export function formatBillingPeriodStatus(status: string): string {
  return PERIOD_STATUS_LABELS[status as BillingPeriodStatus] ?? '未知账期状态'
}

export function formatCloseRunStatus(status: string): string {
  return CLOSE_RUN_STATUS_LABELS[status as CloseRunStatus] ?? '未知关闭结果'
}

export function formatCloseCheckResult(result: string): string {
  return CLOSE_RESULT_LABELS[result as CloseCheckResult] ?? '未知校验结果'
}

export function formatCloseBlocker(code: string): string {
  return BLOCKER_LABELS[code as CloseBlockerCode] ?? '其他关闭校验项'
}

export function periodStatusTagColor(status: string): string {
  if (status === 'CLOSED') return 'success'
  if (status === 'CLOSING') return 'processing'
  return 'blue'
}

export function closeResultTagColor(result: string): string {
  if (result === 'PASS') return 'success'
  if (result === 'ERROR') return 'error'
  return 'warning'
}

export function closeRunTagColor(status: string): string {
  if (status === 'CLOSED') return 'success'
  if (status === 'FAILED') return 'error'
  if (status === 'CHECKING') return 'processing'
  return 'warning'
}

export function formatCheckSummary(summary: Record<string, unknown>, itemCount: number): string {
  const values = [summary.message, summary.description, summary.reason, summary.note]
  const text = values.find((value): value is string => (
    typeof value === 'string'
    && value.trim().length > 0
    && !/[A-Za-z]{3,}/.test(value)
  ))
  if (text) return text
  if (summary.notApplicable === true) return '当前版本不适用，按规则视为通过。'
  return itemCount > 0 ? `发现 ${itemCount} 项需要处理。` : '未发现需要处理的项目。'
}

export function createIdempotencyKey(): string {
  return typeof crypto.randomUUID === 'function' ? crypto.randomUUID() : `${Date.now()}-${Math.random()}`
}
