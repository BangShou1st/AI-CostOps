import { apiClient } from '../../auth/authApi'
import type { BudgetRisk, CostAnomaly, CostForecast, IntelligenceSummary, SavingRecommendation } from './intelligenceTypes'

/** Real M18 contract client. Presentation-only: never recomputes authoritative money. */
export const intelligenceApi = {
  async summary(currency: string): Promise<IntelligenceSummary> {
    return (await apiClient.get<IntelligenceSummary>('/cost-intelligence/summary', { params: { currency } })).data
  },
  async anomalies(currency: string): Promise<CostAnomaly[]> {
    return (await apiClient.get<CostAnomaly[]>('/cost-intelligence/anomalies', { params: { currency } })).data
  },
  async forecasts(currency: string): Promise<CostForecast[]> {
    return (await apiClient.get<CostForecast[]>('/cost-intelligence/forecasts', { params: { currency } })).data
  },
  async recommendations(currency: string): Promise<SavingRecommendation[]> {
    return (await apiClient.get<SavingRecommendation[]>('/cost-intelligence/recommendations', { params: { currency } })).data
  },
  async budgetRisk(scopeType: string, scopeId: string, currency: string): Promise<BudgetRisk> {
    return (await apiClient.get<BudgetRisk>('/cost-intelligence/budget-risks', { params: { scopeType, scopeId, currency } })).data
  },
}
