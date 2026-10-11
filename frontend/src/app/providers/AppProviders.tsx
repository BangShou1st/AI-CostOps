import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ConfigProvider, theme as antdTheme, type ThemeConfig } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import { useState, type PropsWithChildren } from 'react'
import { AuthSessionProvider } from '../../features/auth/AuthSessionProvider'

/**
 * Ant Design theme aligned with the CSS design tokens in styles.css.
 * Keep this seed palette aligned with the CSS tokens when changing it.
 */
const appTheme: ThemeConfig = {
  algorithm: antdTheme.defaultAlgorithm,
  token: {
    colorPrimary: '#2563eb',
    colorPrimaryHover: '#1d4ed8',
    colorInfo: '#2563eb',
    colorLink: '#2563eb',
    colorSuccess: '#1a7f4b',
    colorWarning: '#b45309',
    colorError: '#b42318',
    colorTextBase: '#172033',
    colorBgLayout: '#f5f7fb',
    colorBorder: '#e6eaf2',
    colorBorderSecondary: '#edf0f6',
    borderRadius: 8,
    fontFamily: 'Inter, ui-sans-serif, system-ui, sans-serif',
  },
  components: {
    Card: {
      borderRadiusLG: 14,
      colorBorderSecondary: '#e6eaf2',
    },
    Table: {
      headerBg: '#f7f9fc',
      headerColor: '#475569',
      rowHoverBg: '#f7faff',
      colorBorderSecondary: '#edf0f6',
    },
    Button: {
      borderRadius: 8,
      fontWeight: 600,
    },
    Menu: {
      itemBorderRadius: 8,
    },
    Statistic: {
      titleFontSize: 13,
      contentFontSize: 26,
    },
  },
}

export function AppProviders({ children }: PropsWithChildren) {
  const [queryClient] = useState(() => new QueryClient({
    defaultOptions: {
      queries: { retry: false },
    },
  }))

  return (
    <ConfigProvider locale={zhCN} theme={appTheme}>
      <QueryClientProvider client={queryClient}><AuthSessionProvider>{children}</AuthSessionProvider></QueryClientProvider>
    </ConfigProvider>
  )
}
