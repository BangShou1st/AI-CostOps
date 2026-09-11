import { apiClient } from '../../auth/authApi'
import type { AdvisorJob, AdvisorProfile, AdvisorProfileInput } from './advisorTypes'

/** Real M18 AI Advisor contract client. */
export const advisorApi = {
  async profile(): Promise<AdvisorProfile> {
    return (await apiClient.get<AdvisorProfile>('/ai-advisor/profile')).data
  },
  async updateProfile(input: AdvisorProfileInput): Promise<AdvisorProfile> {
    return (await apiClient.put<AdvisorProfile>('/ai-advisor/profile', input)).data
  },
  async requestExplanation(subjectType: string, subjectId: number): Promise<AdvisorJob> {
    return (await apiClient.post<AdvisorJob>('/ai-advisor/explanations', { subjectType, subjectId })).data
  },
  async explanation(id: number): Promise<AdvisorJob> {
    return (await apiClient.get<AdvisorJob>(`/ai-advisor/explanations/${id}`)).data
  },
  async retry(id: number): Promise<AdvisorJob> {
    return (await apiClient.post<AdvisorJob>(`/ai-advisor/explanations/${id}/retry`)).data
  },
}
