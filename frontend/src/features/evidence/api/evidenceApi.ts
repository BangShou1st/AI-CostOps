import { apiClient } from '../../auth/authApi'
import type { PageResponse } from '../../../api/pagination'
import type { EvidenceSummary } from './evidenceTypes'

export const evidenceApi = {
  async listEvidence(page: number, size: number) {
    return (await apiClient.get<PageResponse<EvidenceSummary>>('/evidence', { params: { page, size } })).data
  },
  async getEvidence(id: string) {
    return (await apiClient.get<EvidenceSummary>(`/evidence/${encodeURIComponent(id)}`)).data
  },
}
