import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import { useState, type PropsWithChildren } from 'react'
import { AuthSessionProvider } from '../../features/auth/AuthSessionProvider'

export function AppProviders({ children }: PropsWithChildren) {
  const [queryClient] = useState(() => new QueryClient({
    defaultOptions: {
      queries: { retry: false },
    },
  }))

  return (
    <ConfigProvider locale={zhCN}>
      <QueryClientProvider client={queryClient}><AuthSessionProvider>{children}</AuthSessionProvider></QueryClientProvider>
    </ConfigProvider>
  )
}
