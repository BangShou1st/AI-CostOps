import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from '../auth/AuthSessionProvider'
import { gatewayApi } from './api/gatewayApi'
import { ModelPricingPage } from './ModelPricingPage'

vi.mock('../auth/AuthSessionProvider', () => ({ useAuth: vi.fn() }))
vi.mock('./api/gatewayApi', () => ({
  gatewayApi: { listModels: vi.fn(), listProviderModels: vi.fn(), listPricingVersions: vi.fn(), createPricingVersion: vi.fn(), activatePricingVersion: vi.fn() },
}))

const mockedUseAuth = vi.mocked(useAuth)
const mockedGateway = vi.mocked(gatewayApi)

beforeEach(() => vi.clearAllMocks())

describe('ModelPricingPage governance', () => {
  it('presents ACTIVE truth, effective time and exact rates', async () => {
    mockedUseAuth.mockReturnValue({
      status: 'authenticated',
      user: { id: '1', email: 'a@e.com', displayName: 'A', organizationId: '1', organizationMemberId: '3', permissions: ['PROVIDER_ACCOUNT_READ'] },
      login: vi.fn(), refreshMe: vi.fn(), logout: vi.fn(),
    } as ReturnType<typeof useAuth>)
    mockedGateway.listModels.mockResolvedValue([])
    mockedGateway.listProviderModels.mockResolvedValue([])
    mockedGateway.listPricingVersions.mockResolvedValue([{
      id: '9', providerAccountId: '5', providerModelId: '77', version: 2, currency: 'USD', status: 'ACTIVE',
      effectiveFrom: '2026-09-01T00:00:00Z', effectiveTo: null, createdAt: '2026-09-01T00:00:00Z', activatedAt: '2026-09-02T00:00:00Z',
      rates: [{ id: '1', dimensionCode: 'INPUT_TOKEN', unitQuantity: 1000000, unitPrice: '30.00' as unknown as number }],
    }])
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter><ModelPricingPage /></MemoryRouter>
      </QueryClientProvider>,
    )
    expect(await screen.findByText('金融治理口径')).toBeInTheDocument()
    expect(await screen.findByText('已启用')).toBeInTheDocument()
    expect(screen.getByText(/INPUT_TOKEN \$30\.00\/1000000/)).toBeInTheDocument()
    expect(screen.getByText(/长期/)).toBeInTheDocument()
  })
})
