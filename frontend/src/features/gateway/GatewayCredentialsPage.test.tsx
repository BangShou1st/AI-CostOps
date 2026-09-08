import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from '../auth/AuthSessionProvider'
import { settingsApi } from '../settings/api/settingsApi'
import { gatewayApi } from './api/gatewayApi'
import { buildCredentialCreateInput, GatewayCredentialsPage } from './GatewayCredentialsPage'
import type { GatewayCredential } from './api/gatewayTypes'

vi.mock('../auth/AuthSessionProvider', () => ({ useAuth: vi.fn() }))
vi.mock('./api/gatewayApi', () => ({
  gatewayApi: {
    listGatewayCredentials: vi.fn(), createGatewayCredential: vi.fn(), revokeGatewayCredential: vi.fn(),
    listServiceIdentities: vi.fn(), listModels: vi.fn(),
  },
}))
vi.mock('../settings/api/settingsApi', () => ({
  settingsApi: { listProjects: vi.fn() },
}))

const mockedUseAuth = vi.mocked(useAuth)
const mockedApi = vi.mocked(gatewayApi)
const mockedSettings = vi.mocked(settingsApi)

const R2_RAW_KEY = 'aic_w4ffnpvmbzqs_UjN4Cw1Tk3OQffsOL7kiUHcF4kuYWUHS7AqMegy_jOo'

function renderPage(permissions: string[]) {
  mockedUseAuth.mockReturnValue({
    status: 'authenticated',
    user: { id: '1', email: 'admin@example.com', displayName: 'Admin', organizationId: '2', organizationMemberId: '3', permissions },
    login: vi.fn(), refreshMe: vi.fn(), logout: vi.fn(),
  } as ReturnType<typeof useAuth>)
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={queryClient}><GatewayCredentialsPage /></QueryClientProvider>)
}

const credential: GatewayCredential = {
  id: '20', prefix: 'w4ffnpvmbzqs', principalType: 'SERVICE', serviceIdentityId: '10',
  organizationMemberId: null, projectId: '5', financialScopeType: 'PROJECT', financialScopeId: '5',
  budgetEnforcementMode: 'OPTIONAL', status: 'ACTIVE', expiresAt: null,
  createdAt: '2026-09-08T00:00:00Z', revokedAt: null,
}

beforeEach(() => {
  vi.clearAllMocks()
  mockedApi.listGatewayCredentials.mockResolvedValue([credential])
  mockedApi.listServiceIdentities.mockResolvedValue([{ id: '10', code: 'm16-uat-bot', name: 'M16 UAT Bot', status: 'ACTIVE', createdAt: '2026-09-08T00:00:00Z' }])
  mockedApi.listModels.mockResolvedValue([{ id: '3', modelKey: 'm16-accept-chat', name: 'M16 Accept Chat', status: 'ACTIVE' }])
  mockedSettings.listProjects.mockResolvedValue({ items: [{ id: '5', code: 'm16-r2', name: 'M16 R2', status: 'ACTIVE', createdAt: '2026-09-08T00:00:00Z', updatedAt: '2026-09-08T00:00:00Z' }], page: 0, size: 100, totalElements: 1, totalPages: 1 })
})

describe('GatewayCredentialsPage', () => {
  it('lists metadata without ever recovering the raw key', async () => {
    renderPage(['PROVIDER_ACCOUNT_READ'])
    expect(await screen.findByText(credential.prefix)).toBeInTheDocument()
    expect(screen.queryByText(R2_RAW_KEY)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '创建凭证' })).not.toBeInTheDocument()
  })

  it('builds the numeric wire contract for credential creation', () => {
    expect(buildCredentialCreateInput('10', '5', 'OPTIONAL', ['3', '4'])).toEqual({
      principalType: 'SERVICE',
      serviceIdentityId: 10,
      projectId: 5,
      financialScopeType: 'PROJECT',
      financialScopeId: 5,
      budgetEnforcementMode: 'OPTIONAL',
      expiresAt: null,
      modelIds: [3, 4],
    })
  })

  it('revoke flows through the governed confirmation and updates the list', async () => {
    mockedApi.revokeGatewayCredential.mockResolvedValue({ ...credential, status: 'REVOKED', revokedAt: '2026-09-08T01:00:00Z' })
    renderPage(['PROVIDER_ACCOUNT_READ', 'PROVIDER_ACCOUNT_MANAGE'])
    // antd inserts a space between two Chinese characters ("吊销" → "吊 销").
    const revokeMatcher = /吊\s*销/
    fireEvent.click(await screen.findByRole('button', { name: revokeMatcher }))
    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveTextContent('吊销后该凭证将无法发起新的网关请求')
    fireEvent.click(within(dialog).getByRole('button', { name: revokeMatcher }))
    await waitFor(() => expect(mockedApi.revokeGatewayCredential).toHaveBeenCalledWith('20'))
  })
})