import { apiClient } from '../../auth/authApi'

/** Allocation workflow types; ids and money are decimal strings. */
export type AllocationDecisionSource = 'MANUAL' | 'RULE'
export type AllocationDecisionStatus = 'DRAFT' | 'CONFIRMED' | 'SUPERSEDED'
export type ProposalStatus = 'CREATED' | 'REUSED' | 'NO_MATCH'
export type ProposalReason = 'NO_EFFECTIVE_TIME' | 'NO_RULE_MATCH'

export interface AllocationRuleTrace {
  id: string
  ruleKey: string
  version: number
  priority: number
}

export interface AllocationLine {
  id: string
  lineIndex: number
  allocatedAmount: string
  currency: string
  projectId: string | null
  costCenterId: string | null
  teamId: string | null
}

export interface AllocationDecision {
  id: string
  subjectType: 'CHARGE_FACT' | 'EXPENSE_CLAIM'
  chargeFactId: string | null
  expenseClaimId: string | null
  source: AllocationDecisionSource
  status: AllocationDecisionStatus
  allocationRule: AllocationRuleTrace | null
  createdByMemberId: string | null
  createdAt: string
  lines: AllocationLine[]
}

export interface AllocationProposal {
  status: ProposalStatus
  decision: AllocationDecision | null
  ruleTrace: AllocationRuleTrace | null
  reason: ProposalReason | null
}

/** One editable allocation line; exactly one target is set. */
export interface AllocationLineInput {
  allocatedAmount: string
  currency: string
  projectId: string | null
  costCenterId: string | null
  teamId: string | null
}

export type AllocationTargetType = 'PROJECT' | 'COST_CENTER' | 'TEAM'

/** Safe ref of one ACTIVE same-org allocatable row (allocation target directory). */
export interface AllocationTargetRef {
  type: AllocationTargetType
  id: string
  name: string
}

export const allocationApi = {
  async listTargets(): Promise<AllocationTargetRef[]> {
    return (await apiClient.get<AllocationTargetRef[]>('/allocation-targets')).data
  },
  async listDecisionsByCharge(chargeId: string): Promise<AllocationDecision[]> {
    return (await apiClient.get<AllocationDecision[]>(
      `/costs/charges/${encodeURIComponent(chargeId)}/allocation-decisions`)).data
  },
  async getDecision(decisionId: string): Promise<AllocationDecision> {
    return (await apiClient.get<AllocationDecision>(
      `/allocation-decisions/${encodeURIComponent(decisionId)}`)).data
  },
  async createManualDraft(chargeId: string, lines: AllocationLineInput[], idempotencyKey: string): Promise<AllocationDecision> {
    return (await apiClient.post<AllocationDecision>(
      `/costs/charges/${encodeURIComponent(chargeId)}/allocation-decisions/manual`,
      { lines },
      { headers: { 'Idempotency-Key': idempotencyKey } },
    )).data
  },
  async replaceLines(decisionId: string, lines: AllocationLineInput[]): Promise<AllocationDecision> {
    // Naturally idempotent full replacement: no Idempotency-Key is sent.
    return (await apiClient.put<AllocationDecision>(
      `/allocation-decisions/${encodeURIComponent(decisionId)}/lines`,
      { lines },
    )).data
  },
  async confirm(decisionId: string, idempotencyKey: string): Promise<AllocationDecision> {
    return (await apiClient.post<AllocationDecision>(
      `/allocation-decisions/${encodeURIComponent(decisionId)}/confirm`,
      undefined,
      { headers: { 'Idempotency-Key': idempotencyKey } },
    )).data
  },
  async propose(chargeId: string, idempotencyKey: string): Promise<AllocationProposal> {
    return (await apiClient.post<AllocationProposal>(
      `/costs/charges/${encodeURIComponent(chargeId)}/allocation-proposal`,
      undefined,
      { headers: { 'Idempotency-Key': idempotencyKey } },
    )).data
  },
  // Expense allocation
  async listDecisionsByExpense(expenseId: string): Promise<AllocationDecision[]> {
    return (await apiClient.get<AllocationDecision[]>(
      `/expenses/${encodeURIComponent(expenseId)}/allocation-decisions`)).data
  },
  async createManualDraftForExpense(expenseId: string, lines: AllocationLineInput[], idempotencyKey: string): Promise<AllocationDecision> {
    return (await apiClient.post<AllocationDecision>(
      `/expenses/${encodeURIComponent(expenseId)}/allocation-decisions/manual`,
      { lines },
      { headers: { 'Idempotency-Key': idempotencyKey } },
    )).data
  },
}
