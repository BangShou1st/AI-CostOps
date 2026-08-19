import { apiClient } from '../../auth/authApi'
import type { PageResponse } from '../../../api/pagination'

// -- Types (strictly mirror docs/02-development/api/openapi.yaml) --
export type CommitmentStatus =
  | 'REQUESTED'
  | 'ACTIVE'
  | 'PARTIALLY_CONSUMED'
  | 'CONSUMED'
  | 'RELEASED'
  | 'REJECTED'
  | 'CANCELED'

export type ApprovalCaseStatus = 'PENDING' | 'NEEDS_INFO' | 'APPROVED' | 'REJECTED' | 'CANCELED'
export type ApprovalActionType = 'SUBMIT' | 'REQUEST_INFO' | 'RESUBMIT' | 'APPROVE' | 'REJECT' | 'CANCEL'

export interface ApprovalActionResponse {
  id: string
  approvalCaseId: string
  actorMemberId: string
  actionType: ApprovalActionType
  fromState: string | null
  toState: string | null
  comment: string | null
  createdAt: string
}

export interface CommitmentResponse {
  id: string
  budgetId: string
  status: CommitmentStatus
  requestedAmount: string
  approvedAmount: string | null
  remainingAmount: string | null
  version: number
  createdAt: string
  updatedAt: string
  approvalCaseId: string | null
  approvalStatus: ApprovalCaseStatus | null
  /** Append-only approval action history; never truncated client-side. */
  history: ApprovalActionResponse[]
}

export interface CommitmentListParams {
  page: number
  size: number
  budgetId?: string
  status?: CommitmentStatus
}

export interface CreateCommitmentBody {
  requestedAmount: string
  currency: string
}

export interface ExpectedVersionBody {
  expectedVersion: number
}

export interface RejectCommitmentBody extends ExpectedVersionBody {
  comment?: string
}

export const commitmentApi = {
  async list(params: CommitmentListParams): Promise<PageResponse<CommitmentResponse>> {
    return (await apiClient.get<PageResponse<CommitmentResponse>>('/commitments', { params })).data
  },
  async get(commitmentId: string): Promise<CommitmentResponse> {
    return (await apiClient.get<CommitmentResponse>('/commitments/' + encodeURIComponent(commitmentId))).data
  },
  async create(budgetId: string, body: CreateCommitmentBody, idempotencyKey: string): Promise<CommitmentResponse> {
    return (await apiClient.post<CommitmentResponse>(
      '/budgets/' + encodeURIComponent(budgetId) + '/commitments', body,
      { headers: { 'Idempotency-Key': idempotencyKey } },
    )).data
  },
  async approve(commitmentId: string, body: ExpectedVersionBody, idempotencyKey: string): Promise<CommitmentResponse> {
    return (await apiClient.post<CommitmentResponse>(
      '/commitments/' + encodeURIComponent(commitmentId) + '/approve', body,
      { headers: { 'Idempotency-Key': idempotencyKey } },
    )).data
  },
  async reject(commitmentId: string, body: RejectCommitmentBody, idempotencyKey: string): Promise<CommitmentResponse> {
    return (await apiClient.post<CommitmentResponse>(
      '/commitments/' + encodeURIComponent(commitmentId) + '/reject', body,
      { headers: { 'Idempotency-Key': idempotencyKey } },
    )).data
  },
  async cancel(commitmentId: string, body: ExpectedVersionBody, idempotencyKey: string): Promise<CommitmentResponse> {
    return (await apiClient.post<CommitmentResponse>(
      '/commitments/' + encodeURIComponent(commitmentId) + '/cancel', body,
      { headers: { 'Idempotency-Key': idempotencyKey } },
    )).data
  },
  async release(commitmentId: string, body: ExpectedVersionBody, idempotencyKey: string): Promise<CommitmentResponse> {
    return (await apiClient.post<CommitmentResponse>(
      '/commitments/' + encodeURIComponent(commitmentId) + '/release', body,
      { headers: { 'Idempotency-Key': idempotencyKey } },
    )).data
  },
}

export const commitmentKeys = {
  /** Prefix filter: invalidates every paged commitment list. */
  lists: () => ['commitment', 'list'] as const,
  list: (params: CommitmentListParams) => ['commitment', 'list', params] as const,
  /** Partial-match filter: all commitment lists of one budget. */
  byBudget: (budgetId: string) => ['commitment', 'list', { budgetId }] as const,
  detail: (commitmentId: string) => ['commitment', 'detail', commitmentId] as const,
}
