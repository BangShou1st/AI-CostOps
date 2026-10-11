import type { ExpenseClaimStatus } from './api/expenseApi'

// One exhaustive mapping shared by employee and finance lists/details.
export const EXPENSE_STATUS_LABEL: Record<ExpenseClaimStatus, string> = {
  DRAFT: '草稿', SUBMITTED: '已提交', NEEDS_INFO: '需补充', APPROVED: '已批准',
  POSTED: '已记账', REJECTED: '已拒绝', CANCELED: '已取消',
}

export const EXPENSE_STATUS_COLOR: Record<ExpenseClaimStatus, string> = {
  DRAFT: 'default', SUBMITTED: 'processing', NEEDS_INFO: 'warning', APPROVED: 'success',
  POSTED: 'blue', REJECTED: 'error', CANCELED: 'default',
}
