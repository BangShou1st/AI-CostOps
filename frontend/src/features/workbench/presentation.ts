import { formatMoney } from '../../lib/money'
import type {
  WorkbenchBudgetVarianceLine,
  WorkbenchCurrencyAmount,
} from './api/workbenchTypes'

/** Fixed-scale decimal amounts are displayed as "amount currency" pairs. */
export function moneyAmount(amount: string, currency: string): string {
  return formatMoney(amount, currency)
}

export function currencyAmounts(lines: WorkbenchCurrencyAmount[]): string[] {
  return lines.map((line) => moneyAmount(line.amount, line.currency))
}

export const PERIOD_STATUS_LABEL: Record<string, string> = {
  OPEN: '进行中',
  CLOSING: '结账中',
  CLOSED: '已结账',
}

export function budgetOverrunCount(lines: WorkbenchBudgetVarianceLine[]): number {
  return lines.filter((line) => line.overBudget).length
}
