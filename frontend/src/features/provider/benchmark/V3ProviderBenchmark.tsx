import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useState } from 'react'
import { AuthSessionProvider } from '../../auth/AuthSessionProvider'
import { ConnectionDetailPage } from '../ConnectionDetailPage'
import { ProviderConnectionsPage } from '../ProviderConnectionsPage'
import { ProviderGalleryPage } from '../ProviderGalleryPage'
import { ProviderModelsPage } from '../ProviderModelsPage'
import { capabilitiesFixture, connectionFixture, discoveryFixture, promotionFixture, templateFixture } from './providerFixture'

/** DEV-ONLY visual benchmark harnesses. Never linked from production navigation. */
function Shell(props: { children: React.ReactNode }) {
  const [client] = useState(() => new QueryClient({ defaultOptions: { queries: { retry: false } } }))
  return (
    <QueryClientProvider client={client}>
      <AuthSessionProvider>{props.children}</AuthSessionProvider>
    </QueryClientProvider>
  )
}

export function V3GalleryBenchmark() {
  return <Shell><ProviderGalleryPage preview={{ templates: templateFixture, connections: connectionFixture }} /></Shell>
}

export function V3ConnectionsBenchmark() {
  return <Shell><ProviderConnectionsPage preview={{ connections: connectionFixture }} /></Shell>
}

export function V3ConnectionDetailBenchmark() {
      <ConnectionDetailPage preview={{ connection: connectionFixture[0], revisions: [connectionFixture[0], { ...connectionFixture[0], id: 10, version: 2, status: 'RETIRED', retiredAt: '2026-08-20T00:00:00Z' }] }} />
}

export function V3ModelsBenchmark() {
  return (
    <Shell>
      <ProviderModelsPage preview={{ connections: connectionFixture, rows: discoveryFixture, capabilities: capabilitiesFixture, promotion: promotionFixture }} />
    </Shell>
  )
}
