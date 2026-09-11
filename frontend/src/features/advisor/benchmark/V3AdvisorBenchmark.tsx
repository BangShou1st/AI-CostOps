import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useState } from 'react'
import { AuthSessionProvider } from '../../auth/AuthSessionProvider'
import { AdvisorPage } from '../AdvisorPage'
import { advisorCompletedFixture, advisorFailedFixture, advisorProfileFixture, advisorRunningFixture } from './advisorFixture'

/** DEV-ONLY visual benchmark harnesses. Never linked from production navigation. */
function Shell(props: { children: React.ReactNode }) {
  const [client] = useState(() => new QueryClient({ defaultOptions: { queries: { retry: false } } }))
  return (
    <QueryClientProvider client={client}>
      <AuthSessionProvider>{props.children}</AuthSessionProvider>
    </QueryClientProvider>
  )
}

export function V3AdvisorCompletedBenchmark() {
  return <Shell><AdvisorPage preview={{ profile: advisorProfileFixture, job: advisorCompletedFixture }} /></Shell>
}

export function V3AdvisorRunningBenchmark() {
  return <Shell><AdvisorPage preview={{ profile: advisorProfileFixture, job: advisorRunningFixture }} /></Shell>
}

export function V3AdvisorFailedBenchmark() {
  return <Shell><AdvisorPage preview={{ profile: advisorProfileFixture, job: advisorFailedFixture }} /></Shell>
}
