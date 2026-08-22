export interface WorkbenchPeriodResponse {
  billingPeriodId: string
  periodStart: string
  periodEnd: string
  status: 'OPEN' | 'CLOSING' | 'CLOSED'
}

export interface WorkbenchProviderCostLine {
  providerCode: string
  currency: string
  totalAmount: string
  chargeCount: number
}

export interface WorkbenchProjectCostLine {
  projectId: string
  projectName: string
  currency: string
  totalAmount: string
}

export interface WorkbenchBudgetVarianceLine {
  budgetId: string
  scopeType: 'ORG' | 'PROJECT' | 'TEAM' | 'COST_CENTER'
  scopeId: string
  currency: string
  totalAmount: string
  actualAmount: string
  committedAmount: string
  availableAmount: string
  overBudget: boolean
}

export interface WorkbenchCurrencyAmount {
  currency: string
  amount: string
  chargeCount: number
}

export interface WorkbenchDuplicateCandidates {
  openCount: number
}

export interface WorkbenchPendingApprovals {
  submittedCount: number
  needsInfoCount: number
}

export interface WorkbenchOpenReconciliations {
  activeRunCount: number
  openCaseCount: number
}

export interface WorkbenchCloseStatus {
  status: 'OPEN' | 'CLOSING' | 'CLOSED'
  closing: boolean
  closed: boolean
}

/** Sections the caller has no ORG read grant for are absent from the payload. */
export interface WorkbenchResponse {
  period?: WorkbenchPeriodResponse
  costByProvider?: WorkbenchProviderCostLine[]
  costByProject?: WorkbenchProjectCostLine[]
  budgetVariance?: WorkbenchBudgetVarianceLine[]
  unallocatedCharges?: WorkbenchCurrencyAmount[]
  duplicateCandidates?: WorkbenchDuplicateCandidates
  pendingApprovals?: WorkbenchPendingApprovals
  openReconciliations?: WorkbenchOpenReconciliations
  closeStatus?: WorkbenchCloseStatus
}
