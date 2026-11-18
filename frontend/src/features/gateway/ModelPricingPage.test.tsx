import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from '../auth/AuthSessionProvider'
import { settingsApi } from '../settings/api/settingsApi'
import { gatewayApi } from './api/gatewayApi'
import { buildPricingCreateInput, isValidDecimalRate, ModelPricingPage } from './ModelPricingPage'

vi.mock('../auth/AuthSessionProvider', () => ({ useAuth: vi.fn() }))
vi.mock('./api/gatewayApi', () => ({
  gatewayApi: {
    listModels: vi.fn(), listProviderModels: vi.fn(), listPricingVersions: vi.fn(),
    createPricingVersion: vi.fn(), activatePricingVersion: vi.fn(),
  },
}))
vi.mock('../settings/api/settingsApi', () => ({
  settingsApi: { listProviderAccounts: vi.fn() },
}))

const mockedUseAuth = vi.mocked(useAuth)
const mockedApi = vi.mocked(gatewayApi)
const mockedSettings = vi.mocked(settingsApi)

function renderPage(permissions: string[]) {
  mockedUseAuth.mockReturnValue({
    status: 'authenticated',
    user: { id: '1', email: 'admin@example.com', displayName: 'Admin', organizationId: '2', organizationMemberId: '3', permissions },
    login: vi.fn(), refreshMe: vi.fn(), logout: vi.fn(),
  } as ReturnType<typeof useAuth>)
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={queryClient}><ModelPricingPage /></QueryClientProvider>)
}

beforeEach(() => {
  vi.clearAllMocks()
  mockedApi.listModels.mockResolvedValue([{ id: '3', modelKey: 'm16-accept-chat', name: 'M16 Accept Chat', status: 'ACTIVE' }])
  mockedApi.listProviderModels.mockResolvedValue([
    { id: '1', providerCode: 'M16MOCK', modelId: '3', providerModelName: 'm16-mock-chat', status: 'ACTIVE', routingEligible: true },
  ])
  mockedApi.listPricingVersions.mockResolvedValue([])
  mockedSettings.listProviderAccounts.mockResolvedValue({
    items: [{ id: '106', providerCode: 'M16MOCK', displayName: 'M16 R2 Mock', externalAccountRef: null, status: 'ACTIVE', metadata: {}, createdAt: '2026-09-08T00:00:00Z', updatedAt: '2026-09-08T00:00:00Z' }],
    page: 0, size: 100, totalElements: 1, totalPages: 1,
  })
})

describe('ModelPricingPage', () => {
  it('renders catalog and provider models', async () => {
    renderPage(['PROVIDER_ACCOUNT_READ'])
    expect(await screen.findByText('m16-accept-chat')).toBeInTheDocument()
    expect(screen.getByText('m16-mock-chat')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '创建定价版本' })).not.toBeInTheDocument()
  })

  it('keeps decimal-string money and numeric ids on the pricing wire', () => {
    const input = buildPricingCreateInput('106', '1', 1000000, '30.00000000', '60.00000000')
    expect(input).toEqual(expect.objectContaining({
      providerAccountId: 106,
      providerModelId: 1,
      currency: 'USD',
      rates: [
        { dimensionCode: 'INPUT_TOKEN', unitQuantity: 1000000, unitPrice: '30.00000000' },
        { dimensionCode: 'OUTPUT_TOKEN', unitQuantity: 1000000, unitPrice: '60.00000000' },
      ],
    }))
  })

  it('validates rates as up-to-8-decimal strings before submit', () => {
    expect(isValidDecimalRate('30')).toBe(true)
    expect(isValidDecimalRate('30.00000000')).toBe(true)
    expect(isValidDecimalRate('0.0001')).toBe(true)
    expect(isValidDecimalRate('30.123456789')).toBe(false)
    expect(isValidDecimalRate('abc')).toBe(false)
    expect(isValidDecimalRate('-1.5')).toBe(false)
  })

  it('offers a later active provider account and submits it for pricing creation', async () => {
    mockedApi.createPricingVersion.mockResolvedValue({
      id: '8', providerAccountId: '207', providerModelId: '1', version: 1, currency: 'USD',
      status: 'DRAFT', effectiveFrom: '2026-09-08T00:00:00Z', effectiveTo: null,
      createdAt: '2026-09-08T00:00:00Z', activatedAt: null, rates: [],
    })
    mockedSettings.listProviderAccounts
      .mockResolvedValueOnce({ items: [], page: 0, size: 200, totalElements: 1, totalPages: 2 })
      .mockResolvedValueOnce({
        items: [{ id: '207', providerCode: 'M16MOCK', displayName: 'Later account', externalAccountRef: null, status: 'ACTIVE', metadata: {}, createdAt: '', updatedAt: '' }],
        page: 1, size: 200, totalElements: 1, totalPages: 2,
      })

    renderPage(['PROVIDER_ACCOUNT_READ', 'PROVIDER_ACCOUNT_MANAGE'])
    fireEvent.click(await screen.findByRole('button', { name: '创建定价版本' }))

    fireEvent.mouseDown(await screen.findByLabelText('服务商账号'))
    fireEvent.click(await screen.findByText('Later account（M16MOCK）'))
    fireEvent.mouseDown(screen.getByRole('combobox', { name: '服务商模型' }))
    const providerModelOptions = await screen.findAllByText('m16-mock-chat')
    fireEvent.click(providerModelOptions[providerModelOptions.length - 1])
    fireEvent.change(screen.getByLabelText('输入令牌单价（每单位，USD）'), { target: { value: '30.00000000' } })
    fireEvent.change(screen.getByLabelText('输出令牌单价（每单位，USD）'), { target: { value: '60.00000000' } })
    fireEvent.click(screen.getByRole('button', { name: '创建草稿' }))

    await waitFor(() => expect(mockedApi.createPricingVersion).toHaveBeenCalledTimes(1))
    expect(mockedApi.createPricingVersion.mock.calls[0][0]).toEqual(expect.objectContaining({
      providerAccountId: 207,
      providerModelId: 1,
    }))
    expect(mockedSettings.listProviderAccounts).toHaveBeenNthCalledWith(1, 0, 200, 'ACTIVE', expect.any(AbortSignal))
    expect(mockedSettings.listProviderAccounts).toHaveBeenNthCalledWith(2, 1, 200, 'ACTIVE', expect.any(AbortSignal))
  })

  it('activates a draft version through the governed action', async () => {
    mockedApi.listPricingVersions.mockResolvedValue([{
      id: '7', providerAccountId: '106', providerModelId: '1', version: 1, currency: 'USD',
      status: 'DRAFT', effectiveFrom: '2026-09-08T00:00:00Z', effectiveTo: null,
      createdAt: '2026-09-08T00:00:00Z', activatedAt: null, rates: [],
    }])
    renderPage(['PROVIDER_ACCOUNT_READ', 'PROVIDER_ACCOUNT_MANAGE'])
    fireEvent.click(await screen.findByRole('button', { name: '启用版本' }))
    await waitFor(() => expect(mockedApi.activatePricingVersion).toHaveBeenCalledWith('7'))
  })
})
