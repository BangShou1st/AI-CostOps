import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from '../../auth/AuthSessionProvider'
import { settingsApi } from '../api/settingsApi'
import { RoutingPoliciesPage } from './RoutingPoliciesPage'

vi.mock('../../auth/AuthSessionProvider', () => ({ useAuth: vi.fn() }))
vi.mock('../api/settingsApi', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/settingsApi')>()),
  settingsApi: {
    listRoutingPolicies: vi.fn(),
    listRoutingOptions: vi.fn(),
    updateRoutingPolicy: vi.fn(),
    activateRoutingPolicy: vi.fn(),
    createRoutingPolicyRevision: vi.fn(),
    createRoutingPolicy: vi.fn(),
  },
}))

const mockedUseAuth = vi.mocked(useAuth)
const mockedSettings = vi.mocked(settingsApi)

function authAs(permissions: string[]) {
  mockedUseAuth.mockReturnValue({
    status: 'authenticated',
    user: { id: '1', email: 'a@e.com', displayName: 'A', organizationId: '1', organizationMemberId: '3', permissions },
    login: vi.fn(), refreshMe: vi.fn(), logout: vi.fn(),
  } as ReturnType<typeof useAuth>)
}

const policy = {
  id: '2', organizationId: '1', projectId: null, modelId: '11', version: 1, status: 'ACTIVE' as const,
  candidates: [{ id: 'c1', providerAccountId: '5', providerModelId: '77', priority: 0, status: 'ACTIVE' as const, privacyRegionCode: null }],
}

beforeEach(() => vi.clearAllMocks())

describe('RoutingPoliciesPage governance', () => {
  it('states human-only governance and surfaces savings deep links', async () => {
    authAs(['PROVIDER_ACCOUNT_READ', 'PROVIDER_ACCOUNT_MANAGE'])
    mockedSettings.listRoutingPolicies.mockResolvedValue({ items: [policy], page: 0, size: 100, totalElements: 1, totalPages: 1 })
    window.history.pushState({}, '', '/settings/routing-policies?recommendationId=3')
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/settings/routing-policies?recommendationId=3']}>
          <RoutingPoliciesPage />
        </MemoryRouter>
      </QueryClientProvider>,
    )
    expect(await screen.findByText(/人工治理/)).toBeInTheDocument()
    expect(screen.getByText(/来自节约建议 #3 的检查/)).toBeInTheDocument()
    expect(screen.getByText('回节约建议')).toBeInTheDocument()
    expect(screen.getByText(/节约建议不会自动修改路由/)).toBeInTheDocument()
  })
})
