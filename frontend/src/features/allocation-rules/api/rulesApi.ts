import { apiClient } from '../../auth/authApi'
import type { PageResponse } from '../../../api/pagination'

/** Allocation rule types; ids are decimal strings. */
export type AllocationRuleMatchType = 'PROVIDER_API_KEY' | 'PROVIDER_PROJECT' | 'PROVIDER_USER'
export type AllocationRuleStatus = 'ACTIVE' | 'ARCHIVED'

export interface AllocationRule {
  id: string
  ruleKey: string
  version: number
  name: string
  providerCode: string
  providerAccountId: string | null
  matchHintType: AllocationRuleMatchType
  matchValue: string
  priority: number
  targetProjectId: string | null
  targetCostCenterId: string | null
  targetTeamId: string | null
  effectiveFrom: string
  effectiveTo: string | null
  status: AllocationRuleStatus
  createdByMemberId: string
  createdAt: string
}

export interface RuleVersionInput {
  name: string
  providerCode: string
  providerAccountId: string | null
  matchHintType: AllocationRuleMatchType
  matchValue: string
  priority: number
  targetProjectId: string | null
  targetCostCenterId: string | null
  targetTeamId: string | null
  effectiveFrom: string
  effectiveTo: string | null
}

export interface RuleListParams {
  page: number
  size: number
}

export const rulesApi = {
  async listRules(params: RuleListParams): Promise<PageResponse<AllocationRule>> {
    return (await apiClient.get<PageResponse<AllocationRule>>('/allocation-rules', { params })).data
  },
  async getRule(ruleId: string): Promise<AllocationRule> {
    return (await apiClient.get<AllocationRule>(`/allocation-rules/${encodeURIComponent(ruleId)}`)).data
  },
  async createVersion(ruleKey: string, definition: RuleVersionInput, idempotencyKey: string): Promise<AllocationRule> {
    return (await apiClient.post<AllocationRule>(
      `/allocation-rules/${encodeURIComponent(ruleKey)}/versions`,
      definition,
      { headers: { 'Idempotency-Key': idempotencyKey } },
    )).data
  },
  async archive(ruleId: string, idempotencyKey: string): Promise<AllocationRule> {
    return (await apiClient.post<AllocationRule>(
      `/allocation-rules/${encodeURIComponent(ruleId)}/archive`,
      undefined,
      { headers: { 'Idempotency-Key': idempotencyKey } },
    )).data
  },
}
