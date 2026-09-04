import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from '../../auth/AuthSessionProvider'
import { settingsApi } from '../api/settingsApi'
import { RoutingPoliciesPage } from './RoutingPoliciesPage'
import type { RoutingPolicyPage } from './types'

vi.mock('../../auth/AuthSessionProvider', () => ({ useAuth: vi.fn() }))
vi.mock('../api/settingsApi', () => ({
  settingsApi: {
    listRoutingPolicies: vi.fn(), listRoutingOptions: vi.fn(),
    updateRoutingPolicy: vi.fn(), activateRoutingPolicy: vi.fn(),
    createRoutingPolicyRevision: vi.fn(), createRoutingPolicy: vi.fn(),
  },
}))

const mockedUseAuth = vi.mocked(useAuth)
const mockedApi = vi.mocked(settingsApi)
const policy: RoutingPolicyPage['items'][number] = {
  id: '10', organizationId: '2', projectId: null, modelId: '7', version: 2, status: 'ACTIVE',
  candidates: [{ id: '11', providerAccountId: '12', providerModelId: '13', priority: 0, status: 'ACTIVE', privacyRegionCode: null }],
}

beforeEach(() => {
  vi.clearAllMocks()
  mockedUseAuth.mockReturnValue({
    status: 'authenticated',
    user: { id: '1', email: 'admin@example.com', displayName: 'Admin', organizationId: '2', organizationMemberId: '11', permissions: ['PROVIDER_ACCOUNT_READ'] },
    login: vi.fn(), refreshMe: vi.fn(), logout: vi.fn(),
  } as ReturnType<typeof useAuth>)
  mockedApi.listRoutingPolicies.mockResolvedValue({ items: [policy], page: 0, size: 100, totalElements: 1, totalPages: 1 })
  mockedApi.listRoutingOptions.mockResolvedValue([])
})

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={queryClient}><RoutingPoliciesPage /></QueryClientProvider>)
}

describe('RoutingPoliciesPage', () => {
  it('reader sees history without mutation controls', async () => {
    renderPage()
    expect(await screen.findByText('组织默认')).toBeInTheDocument()
    expect(screen.getByText('已启用')).toBeInTheDocument()
    expect(screen.queryByText('创建策略')).not.toBeInTheDocument()
    expect(screen.queryByText('创建新版本')).not.toBeInTheDocument()
  })

  it('shows a Chinese empty state for an organization with no policies', async () => {
    mockedApi.listRoutingPolicies.mockResolvedValue({ items: [], page: 0, size: 100, totalElements: 0, totalPages: 0 })
    renderPage()
    expect(await screen.findByText('该组织暂无路由策略。')).toBeInTheDocument()
  })
})
