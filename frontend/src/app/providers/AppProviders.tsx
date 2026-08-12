import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useState, type PropsWithChildren } from 'react'
import { AuthSessionProvider } from '../../features/auth/AuthSessionProvider'

export function AppProviders({ children }: PropsWithChildren) {
  const [queryClient] = useState(() => new QueryClient({
    defaultOptions: {
      queries: { retry: false },
    },
  }))

  return <QueryClientProvider client={queryClient}><AuthSessionProvider>{children}</AuthSessionProvider></QueryClientProvider>
}
