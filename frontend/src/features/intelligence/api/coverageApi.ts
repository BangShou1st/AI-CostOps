import { apiClient } from '../../auth/authApi'
import type { CoverageReviewRequest, CoverageScope, CoverageScopeOption, CoverageView } from './coverageTypes'

const root = '/cost-intelligence/history-coverage'
export const coverageApi = {
  async scopes(search: string): Promise<CoverageScopeOption[]> {
    return (await apiClient.get<CoverageScopeOption[]>(`${root}/scopes`, { params: { search } })).data
  },
  async coverage(currency: string, scope: CoverageScope, scopeId: string, start: string, end: string): Promise<CoverageView> {
    return (await apiClient.get<CoverageView>(root, { params: { currency, scope, scopeId, start, end } })).data
  },
  async review(input: CoverageReviewRequest): Promise<{ revision: number }> {
    return (await apiClient.put<{ revision: number }>(root, input)).data
  },
  async analyze(currency: string): Promise<{ runId: number; status: string }> {
    return (await apiClient.post<{ runId: number; status: string }>(`${root}/analyze`, null, { params: { currency } })).data
  },
}
