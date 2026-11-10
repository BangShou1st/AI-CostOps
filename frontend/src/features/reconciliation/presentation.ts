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

/** 对账算法版本 → 面向用户的中性标签；未知版本回退展示原始枚举。 */
const ALGORITHM_VERSION_LABELS: Record<string, string> = {
  M15_HYBRID_PERIOD_PROVIDER_CURRENCY_V2: '混合模式 · 按账期与币种聚合',
}

/** 整体调整分配目标类型；原始枚举值只用于表单与 API，不直接展示。 */
export const ADJUSTMENT_SCOPE_LABELS: Record<string, string> = {
  PROJECT: '项目',
  COST_CENTER: '成本中心',
  TEAM: '团队',
}

export function formatReconciliationAlgorithmVersion(algorithmVersion: string): string {
  return ALGORITHM_VERSION_LABELS[algorithmVersion] ?? algorithmVersion
}

/**
 * 处理原因代码 → 中文标签。reasonCode 是审核人自由填写的业务代码
 * （后端仅约束非空 ≤64 字符，无固定枚举），已知代码翻译，未知值回退原文。
 */
const CASE_REASON_LABELS: Record<string, string> = {
  OPERATIONAL_DECISION: '运营处理',
}

export function formatReconciliationCaseReason(reasonCode: string): string {
  return CASE_REASON_LABELS[reasonCode] ?? reasonCode
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
