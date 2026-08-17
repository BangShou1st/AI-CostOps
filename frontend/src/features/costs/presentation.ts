import type { ChargeReviewStatus } from './api/costsApi'

/** Presentation labels: API values stay English, display text is Chinese. */
export const REVIEW_STATUS_LABELS: Record<ChargeReviewStatus, string> = {
  CLEAN: '正常',
  SUSPECTED_DUPLICATE: '疑似重复',
  EXCLUDED_DUPLICATE: '重复已排除',
  EXCLUDED_NONCOST: '非成本已排除',
}

export function reviewStatusColor(status: ChargeReviewStatus): string {
  switch (status) {
    case 'CLEAN':
      return 'green'
    case 'SUSPECTED_DUPLICATE':
      return 'orange'
    case 'EXCLUDED_DUPLICATE':
    case 'EXCLUDED_NONCOST':
      return 'red'
  }
}
