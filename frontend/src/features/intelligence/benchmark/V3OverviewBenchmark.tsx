import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useState } from 'react'
import { AuthSessionProvider } from '../../auth/AuthSessionProvider'
import { OverviewPage, type OverviewPreviewData } from '../OverviewPage'
import { overviewFixture } from './overviewFixture'

/**
 * DEV-ONLY visual benchmark harness (never linked from production navigation).
 * Renders the real OverviewPage with deterministic M18-schema fixture data so
 * screenshots exercise real DOM, styles and figure without a live backend.
 * Auth comes from the real provider (anonymous offline) plus an explicit
 * DEV-only override for org scope and budget readability.
 */
export function V3OverviewBenchmark() {
  const [client] = useState(() => new QueryClient({ defaultOptions: { queries: { retry: false } } }))
  const preview: OverviewPreviewData = {
    currency: overviewFixture.currency,
    generatedAt: overviewFixture.generatedAt,
    summary: overviewFixture.summary,
    anomalies: overviewFixture.anomalies,
    forecasts: [overviewFixture.forecast],
    budgetRisk: overviewFixture.budgetRisk,
    recommendations: overviewFixture.recommendations,
  }
  return (
    <QueryClientProvider client={client}>
      <AuthSessionProvider>
        <OverviewPage preview={preview} authOverride={{ organizationId: '1', canReadBudget: true }} />
      </AuthSessionProvider>
    </QueryClientProvider>
  )
}
