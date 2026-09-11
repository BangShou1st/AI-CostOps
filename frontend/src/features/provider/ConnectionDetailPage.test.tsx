import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from '../auth/AuthSessionProvider'
import { ConnectionDetailPage } from './ConnectionDetailPage'
import { providerHubApi } from './api/providerHubApi'
import type { ProviderConnection } from './api/providerHubTypes'

vi.mock('../auth/AuthSessionProvider', () => ({ useAuth: vi.fn() }))
vi.mock('./api/providerHubApi', () => ({
  providerHubApi: {
    connection: vi.fn(),
    revisions: vi.fn(),
    credentials: vi.fn(),
    activate: vi.fn(),
    createRevision: vi.fn(),
    probe: vi.fn(),
  },
}))

const mockedUseAuth = vi.mocked(useAuth)
const mockedApi = vi.mocked(providerHubApi)

function baseConnection(overrides: Partial<ProviderConnection> & { id: number }): ProviderConnection {
  return {
    providerAccountId: 5,
    connectionKind: 'CUSTOM',
    templateCode: 'CUSTOM_OPENAI',
    protocolCode: 'OPENAI_CHAT_COMPLETIONS',
    baseUrl: 'https://llm.example.com',
    completionPath: '/v1/chat/completions',
    modelsPath: '/v1/models',
    authType: 'BEARER',
    networkPolicy: 'DIRECT_PUBLIC_ONLY',
    connectTimeoutMs: 5000,
    responseTimeoutMs: 120000,
    createdAt: '2026-09-01T00:00:00Z',
    activatedAt: null,
    retiredAt: null,
    version: 1,
    status: 'DRAFT',
    ...overrides,
  }
}

function renderDetailPage(pageId: number) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  mockedUseAuth.mockReturnValue({
    status: 'authenticated',
    user: {
      id: '1',
      email: 'a@e.com',
      displayName: 'A',
      organizationId: '1',
      organizationMemberId: '3',
      permissions: ['PROVIDER_ACCOUNT_READ', 'PROVIDER_ACCOUNT_MANAGE'],
    },
    login: vi.fn(),
    refreshMe: vi.fn(),
    logout: vi.fn(),
  } as unknown as ReturnType<typeof useAuth>)
  render(
    <QueryClientProvider client={queryClient}>
    <MemoryRouter initialEntries={['/connections/' + pageId]}>
        <Routes>
          <Route path="/connections/:id" element={<ConnectionDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('ConnectionDetailPage revision activation', () => {
  it('activates the selected DRAFT revision id instead of the page id', async () => {
    const active10 = baseConnection({
      id: 10,
      version: 1,
      status: 'ACTIVE',
      activatedAt: '2026-09-02T00:00:00Z',
    })
    const draft11 = baseConnection({ id: 11, version: 2, status: 'DRAFT' })
    mockedApi.connection.mockResolvedValue(active10)
    mockedApi.revisions.mockResolvedValue([active10, draft11])
    mockedApi.credentials.mockResolvedValue([])
    mockedApi.activate.mockResolvedValue({ ...draft11, status: 'ACTIVE' })

    renderDetailPage(10)

    const activateButton = await screen.findByRole('button', { name: '激活此版' })
    expect(activateButton).toBeInTheDocument()
    fireEvent.click(activateButton)

    await waitFor(() => {
      expect(mockedApi.activate).toHaveBeenCalledTimes(1)
    })
    expect(mockedApi.activate).toHaveBeenCalledWith(11)
    expect(mockedApi.activate).not.toHaveBeenCalledWith(10)
  })
})
