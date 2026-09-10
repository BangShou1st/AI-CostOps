import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from '../auth/AuthSessionProvider'
import { gatewayApi } from './api/gatewayApi'
import { ServiceIdentitiesPage } from './ServiceIdentitiesPage'

vi.mock('../auth/AuthSessionProvider', () => ({ useAuth: vi.fn() }))
vi.mock('./api/gatewayApi', () => ({
  gatewayApi: { listServiceIdentities: vi.fn(), createServiceIdentity: vi.fn() },
}))

const mockedUseAuth = vi.mocked(useAuth)
const mockedApi = vi.mocked(gatewayApi)

function renderPage(permissions: string[]) {
  mockedUseAuth.mockReturnValue({
    status: 'authenticated',
    user: { id: '1', email: 'admin@example.com', displayName: 'Admin', organizationId: '2', organizationMemberId: '3', permissions },
    login: vi.fn(), refreshMe: vi.fn(), logout: vi.fn(),
  } as ReturnType<typeof useAuth>)
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={queryClient}><ServiceIdentitiesPage /></QueryClientProvider>)
}

function okButton() {
  // antd inserts a space between two Chinese characters ("创建" → "创 建").
  return screen.getByRole('button', { name: /^创\s*建$/ })
}

beforeEach(() => {
  vi.clearAllMocks()
  mockedApi.listServiceIdentities.mockResolvedValue([
    { id: '10', code: 'm16-uat-bot', name: 'M16 UAT Bot', status: 'ACTIVE', createdAt: '2026-09-08T00:00:00Z' },
  ])
})

describe('ServiceIdentitiesPage', () => {
  it('reader sees identities without the create control', async () => {
    renderPage(['PROVIDER_ACCOUNT_READ'])
    expect(await screen.findByText('m16-uat-bot')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '创建服务身份' })).not.toBeInTheDocument()
  })

  it('manager creates an identity with the governed code contract', async () => {
    mockedApi.createServiceIdentity.mockResolvedValue({
      id: '11', code: 'r2-bot', name: 'R2 Bot', status: 'ACTIVE', createdAt: '2026-09-08T00:00:00Z',
    })
    renderPage(['PROVIDER_ACCOUNT_READ', 'PROVIDER_ACCOUNT_MANAGE'])
    fireEvent.click(await screen.findByRole('button', { name: '创建服务身份' }))
    fireEvent.change(screen.getByLabelText('代号'), { target: { value: 'r2-bot' } })
    fireEvent.change(screen.getByLabelText('名称'), { target: { value: 'R2 Bot' } })
    fireEvent.click(okButton())
    await waitFor(() => expect(mockedApi.createServiceIdentity).toHaveBeenCalledWith({ code: 'r2-bot', name: 'R2 Bot' }))
  })

  it('blocks an invalid identity code before submitting', async () => {
    renderPage(['PROVIDER_ACCOUNT_READ', 'PROVIDER_ACCOUNT_MANAGE'])
    fireEvent.click(await screen.findByRole('button', { name: '创建服务身份' }))
    fireEvent.change(screen.getByLabelText('代号'), { target: { value: 'Bad Code!' } })
    fireEvent.change(screen.getByLabelText('名称'), { target: { value: 'R2 Bot' } })
    expect(okButton()).toBeDisabled()
    expect(screen.getByRole('alert')).toHaveTextContent('请填写合法代号')
    fireEvent.click(okButton()!)
    expect(mockedApi.createServiceIdentity).not.toHaveBeenCalled()
  })
})