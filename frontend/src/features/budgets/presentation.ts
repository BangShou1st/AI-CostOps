import type { ApprovalCaseStatus, CommitmentStatus } from './api/commitmentApi'
import type { BudgetScopeType } from './api/budgetApi'

export const COMMITMENT_STATUS_LABEL: Record<CommitmentStatus, string> = {
  REQUESTED: '待审批',
  ACTIVE: '已生效',
  PARTIALLY_CONSUMED: '部分消耗',
  CONSUMED: '已消耗',
  RELEASED: '已释放',
  REJECTED: '已拒绝',
  CANCELED: '已取消',
}

export const COMMITMENT_STATUS_COLOR: Record<CommitmentStatus, string> = {
  REQUESTED: 'processing',
  ACTIVE: 'success',
  PARTIALLY_CONSUMED: 'warning',
  CONSUMED: 'default',
  RELEASED: 'default',
  REJECTED: 'error',
  CANCELED: 'default',
}

export const APPROVAL_STATUS_LABEL: Record<ApprovalCaseStatus, string> = {
  PENDING: '待审批',
  NEEDS_INFO: '需补充',
  APPROVED: '已批准',
  REJECTED: '已拒绝',
  CANCELED: '已取消',
}

/** Budget scope types; the raw API enum values are never rewritten. */
export const BUDGET_SCOPE_LABEL: Record<BudgetScopeType, string> = {
  ORG: '组织',
  PROJECT: '项目',
  TEAM: '团队',
  COST_CENTER: '成本中心',
}

/** Budget lifecycle status; unknown future values fall back to the raw value. */
export const BUDGET_STATUS_LABEL: Record<string, string> = {
  ACTIVE: '生效',
}

/**
 * Approval-history from/to state labels: the same states as commitment
 * statuses, plus NONE for "no prior state". Unknown future states fall back
 * to the raw enum value instead of throwing.
 */
export const COMMITMENT_STATE_LABEL: Record<string, string> = {
  NONE: '无',
  ...COMMITMENT_STATUS_LABEL,
}

export function commitmentStateLabel(state: string | null): string {
  if (state === null) return '—'
  return COMMITMENT_STATE_LABEL[state] ?? state
}

/**
 * Business framing for known budget/commitment command failures. Unknown
 * problems fall back to the server detail; the raw stack is never shown.
 */
export function budgetCommandProblemMessage(problem: { code: string; detail: string | null }): string {
  switch (problem.code) {
    case 'STATE_CONFLICT':
      return '预算已发生变化，请刷新最新版本后重试。'
    case 'BUDGET_INSUFFICIENT':
      return '预算可用额度不足。'
    case 'PERIOD_NOT_OPEN':
      return '当前账期不允许执行此操作。'
    default:
      return problem.detail ?? '操作失败'
  }
}
