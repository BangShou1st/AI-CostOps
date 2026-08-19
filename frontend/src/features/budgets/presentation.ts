import type { ApprovalCaseStatus, CommitmentStatus } from './api/commitmentApi'

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

/**
 * Business framing for known budget/commitment command failures. Unknown
 * problems fall back to the server detail; the raw stack is never shown.
 */
export function budgetCommandProblemMessage(problem: { code: string; detail: string | null }): string {
  switch (problem.code) {
    case 'STATE_CONFLICT':
      return 'Budget has changed. Refresh the latest version before retrying.'
    case 'BUDGET_INSUFFICIENT':
      return 'Budget availability is insufficient.'
    case 'PERIOD_NOT_OPEN':
      return 'Current financial period does not allow this action.'
    default:
      return problem.detail ?? '操作失败'
  }
}
