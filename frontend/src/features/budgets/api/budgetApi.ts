import { apiClient } from '../../auth/authApi'
import type { PageResponse } from '../../../api/pagination'

// -- Types (strictly mirror docs/02-development/api/openapi.yaml) --
export type BudgetScopeType = 'ORG' | 'PROJECT' | 'TEAM' | 'COST_CENTER'

export interface BudgetResponse {
  id: string
  billingPeriodId: string
  scopeType: BudgetScopeType
  scopeId: string
  currency: string
  /** DECIMAL(20,8) money always travels as an exact decimal string. */
  totalAmount: string
  actualAmount: string
  committedAmount: string
  /** Server-computed authority: total - actual - committed on the backend. */
  availableAmount: string
  /** Server-computed authority: availableAmount is negative. */
  overBudget: boolean
  status: string
  version: number
  createdAt: string
  updatedAt: string
}

export interface BudgetListParams {
  page: number
  size: number
  billingPeriodId?: string
  scopeType?: BudgetScopeType
  scopeId?: string
}

export interface UpdateBudgetBody {
  totalAmount: string
  expectedVersion: number
}

export interface CreateBudgetBody {
  billingPeriodId: string
  scopeType: BudgetScopeType
  scopeId: string
  currency: string
  totalAmount: string
}

export const budgetApi = {
  async list(params: BudgetListParams): Promise<PageResponse<BudgetResponse>> {
    return (await apiClient.get<PageResponse<BudgetResponse>>('/budgets', { params })).data
  },
  async get(budgetId: string): Promise<BudgetResponse> {
    return (await apiClient.get<BudgetResponse>('/budgets/' + encodeURIComponent(budgetId))).data
  },
  async create(body: CreateBudgetBody): Promise<BudgetResponse> {
    return (await apiClient.post<BudgetResponse>('/budgets', body)).data
  },
  /** Total-only CAS update; financial counters are never writable here. */
  async update(budgetId: string, body: UpdateBudgetBody): Promise<BudgetResponse> {
    return (await apiClient.put<BudgetResponse>('/budgets/' + encodeURIComponent(budgetId), body)).data
  },
}

export const budgetKeys = {
  /** Prefix filter: invalidates every paged budget list. */
  lists: () => ['budget', 'list'] as const,
  list: (params: BudgetListParams) => ['budget', 'list', params] as const,
  detail: (budgetId: string) => ['budget', 'detail', budgetId] as const,
}
