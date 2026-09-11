export const intelligenceKeys = {
  all: ['intelligence'] as const,
  summary: (currency: string) => [...intelligenceKeys.all, 'summary', currency] as const,
  anomalies: (currency: string) => [...intelligenceKeys.all, 'anomalies', currency] as const,
  forecasts: (currency: string) => [...intelligenceKeys.all, 'forecasts', currency] as const,
  recommendations: (currency: string) => [...intelligenceKeys.all, 'recommendations', currency] as const,
}
