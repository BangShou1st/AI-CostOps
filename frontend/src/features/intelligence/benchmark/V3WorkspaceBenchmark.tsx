import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useState } from 'react'
import { AuthSessionProvider } from '../../auth/AuthSessionProvider'
import { AnomaliesPage } from '../AnomaliesPage'
import { ForecastsPage } from '../ForecastsPage'
import { SavingsPage } from '../SavingsPage'
import { overviewFixture } from './overviewFixture'

/** DEV-ONLY visual benchmark harnesses. Never linked from production navigation. */
function Shell(props: { children: React.ReactNode }) {
  const [client] = useState(() => new QueryClient({ defaultOptions: { queries: { retry: false } } }))
  return (
    <QueryClientProvider client={client}>
      <AuthSessionProvider>{props.children}</AuthSessionProvider>
    </QueryClientProvider>
  )
}

export function V3AnomaliesBenchmark() {
  return (
    <Shell>
      <AnomaliesPage preview={{ currency: 'USD', anomalies: overviewFixture.anomalies, summary: overviewFixture.summary }} />
    </Shell>
  )
}

export function V3ForecastsBenchmark() {
  return (
    <Shell>
      <ForecastsPage
        preview={{
          currency: 'USD',
          forecasts: [
            overviewFixture.forecast,
            { ...overviewFixture.forecast, scopeType: 'PROJECT', scopeKey: 'project:atlas', projectedAmount: '4210.55', method: 'RECENT_RUN_RATE', confidence: 'LOW', historyBucketCount: 8, observedThrough: '2026-09-10' },
            { ...overviewFixture.forecast, scopeType: 'TEAM', scopeKey: 'team:core', projectedAmount: '2380.10', method: 'DAMPED_HOLT', confidence: 'HIGH', historyBucketCount: 29, observedThrough: '2026-09-10' },
          ],
        }}
      />
    </Shell>
  )
}

export function V3SavingsBenchmark() {
  return (
    <Shell>
      <SavingsPage preview={{ currency: 'USD', recommendations: overviewFixture.recommendations }} />
    </Shell>
  )
}
