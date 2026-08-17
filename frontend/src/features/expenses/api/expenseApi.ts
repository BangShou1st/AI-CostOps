import { apiClient } from '../../auth/authApi'
import type { PageResponse } from '../../../api/pagination'

// -- Status types --
export type ExpenseClaimStatus = 'DRAFT' | 'SUBMITTED' | 'NEEDS_INFO' | 'APPROVED' | 'REJECTED' | 'CANCELED'
export type ApprovalCaseStatus = 'PENDING' | 'NEEDS_INFO' | 'APPROVED' | 'REJECTED' | 'CANCELED'
export type ApprovalActionType = 'SUBMIT' | 'REQUEST_INFO' | 'RESUBMIT' | 'APPROVE' | 'REJECT' | 'CANCEL'
export type ExpenseReviewStatusFilter = 'ALL' | 'SUBMITTED' | 'NEEDS_INFO' | 'APPROVED'

// -- Response shapes --
export interface ApprovalActionResponse {
  id: string
  actionType: ApprovalActionType
  actorMemberId: string
  fromState: string
  toState: string
  comment: string | null
  createdAt: string
}

export interface ExpenseResponse {
  id: string
  status: ExpenseClaimStatus
  claimantMemberId: string
  evidenceId: string | null
  expenseDate: string
  amount: string
  currency: string
  currentAllocationDecisionId: string | null
  approvalCaseId: string | null
  approvalStatus: ApprovalCaseStatus | null
  postingReady: boolean
  canEdit: boolean
  version: number
  createdAt: string
  updatedAt: string
  history: ApprovalActionResponse[]
}

export interface ExpenseSummaryResponse {
  id: string
  status: ExpenseClaimStatus
  evidenceId: string | null
  expenseDate: string
  amount: string
  currency: string
  approvalStatus: ApprovalCaseStatus | null
  postingReady: boolean
  version: number
  createdAt: string
}

// -- Request bodies --
export interface CreateExpenseBody {
  expenseDate: string
  amount: string
  currency: string
}

export interface EditExpenseBody extends CreateExpenseBody {
  expectedVersion: number
}

export interface ExpectedVersionBody {
  expectedVersion: number
}

export interface CommentBody {
  expectedVersion: number
  comment: string
}

// -- API --
export const expenseApi = {
  // Employee
  async create(body: CreateExpenseBody, idempotencyKey: string): Promise<ExpenseResponse> {
    return (await apiClient.post<ExpenseResponse>('/expenses', body, {
      headers: { 'Idempotency-Key': idempotencyKey },
    })).data
  },
  async listMine(page: number, size: number): Promise<PageResponse<ExpenseSummaryResponse>> {
    return (await apiClient.get<PageResponse<ExpenseSummaryResponse>>('/expenses', {
      params: { page, size },
    })).data
  },
  async get(id: string): Promise<ExpenseResponse> {
    return (await apiClient.get<ExpenseResponse>(`/expenses/${encodeURIComponent(id)}`)).data
  },
  async edit(id: string, body: EditExpenseBody): Promise<ExpenseResponse> {
    return (await apiClient.put<ExpenseResponse>(`/expenses/${encodeURIComponent(id)}`, body)).data
  },
  async submit(id: string, body: ExpectedVersionBody, idempotencyKey: string): Promise<ExpenseResponse> {
    return (await apiClient.post<ExpenseResponse>(`/expenses/${encodeURIComponent(id)}/submit`, body, {
      headers: { 'Idempotency-Key': idempotencyKey },
    })).data
  },
  async cancel(id: string, body: ExpectedVersionBody, idempotencyKey: string): Promise<ExpenseResponse> {
    return (await apiClient.post<ExpenseResponse>(`/expenses/${encodeURIComponent(id)}/cancel`, body, {
      headers: { 'Idempotency-Key': idempotencyKey },
    })).data
  },
  async uploadEvidence(id: string, file: File, expectedVersion: number): Promise<ExpenseResponse> {
    const form = new FormData()
    form.append('file', file)
    form.append('expectedVersion', String(expectedVersion))
    return (await apiClient.post<ExpenseResponse>(
      `/expenses/${encodeURIComponent(id)}/evidence`, form,
      { headers: { 'Content-Type': 'multipart/form-data' } },
    )).data
  },
  evidenceDownloadUrl(id: string): string {
    return `${apiClient.defaults.baseURL}/expenses/${encodeURIComponent(id)}/evidence/download`
  },

  // Finance review
  async listReviewQueue(status: ExpenseReviewStatusFilter, page: number, size: number): Promise<PageResponse<ExpenseSummaryResponse>> {
    return (await apiClient.get<PageResponse<ExpenseSummaryResponse>>('/expense-reviews', {
      params: { status, page, size },
    })).data
  },
  async getForReview(id: string): Promise<ExpenseResponse> {
    return (await apiClient.get<ExpenseResponse>(`/expense-reviews/${encodeURIComponent(id)}`)).data
  },
  async requestInfo(id: string, body: CommentBody, idempotencyKey: string): Promise<ExpenseResponse> {
    return (await apiClient.post<ExpenseResponse>(`/expenses/${encodeURIComponent(id)}/request-info`, body, {
      headers: { 'Idempotency-Key': idempotencyKey },
    })).data
  },
  async approve(id: string, body: ExpectedVersionBody, idempotencyKey: string): Promise<ExpenseResponse> {
    return (await apiClient.post<ExpenseResponse>(`/expenses/${encodeURIComponent(id)}/approve`, body, {
      headers: { 'Idempotency-Key': idempotencyKey },
    })).data
  },
  async reject(id: string, body: CommentBody, idempotencyKey: string): Promise<ExpenseResponse> {
    return (await apiClient.post<ExpenseResponse>(`/expenses/${encodeURIComponent(id)}/reject`, body, {
      headers: { 'Idempotency-Key': idempotencyKey },
    })).data
  },
}

export const expenseKeys = {
  mine: (page: number, size: number) => ['expense', 'mine', { page, size }] as const,
  detail: (id: string) => ['expense', 'detail', id] as const,
  reviewQueue: (status: ExpenseReviewStatusFilter, page: number, size: number) =>
    ['expense', 'reviewQueue', status, { page, size }] as const,
  reviewDetail: (id: string) => ['expense', 'reviewDetail', id] as const,
}
