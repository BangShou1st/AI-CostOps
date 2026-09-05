import type { LedgerEntryType, LedgerSourceType } from './api/ledgerApi'

export const LEDGER_SOURCE_LABEL: Record<LedgerSourceType, string> = {
  PROVIDER_CHARGE: '供应商成本',
  EXPENSE_CLAIM: '报销',
  CORRECTION: '纠正',
  GATEWAY_SETTLEMENT: '网关结算',
  RECONCILIATION_ADJUSTMENT: '对账调整',
}

export const LEDGER_ENTRY_LABEL: Record<LedgerEntryType, string> = {
  COST: '成本',
  CREDIT: '贷项',
  ADJUSTMENT: '调整',
  REVERSAL: '反转',
}

export function formatLedgerStatus(status: string): string {
  const labels: Record<string, string> = {
    POSTED: '已入账',
    REVERSED: '已冲销',
  }
  return labels[status] ?? '其他状态'
}

export function currencyTotals(totals: Record<string, string>): string[] {
  return Object.entries(totals).map(([currency, amount]) => `${amount} ${currency}`)
}

export function lineageLabel(value: string | null | undefined, fallback = '—'): string {
  return value ?? fallback
}
