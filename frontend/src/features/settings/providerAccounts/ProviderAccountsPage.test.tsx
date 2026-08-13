import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from '../../auth/AuthSessionProvider'
import type { PageResponse } from '../../../api/pagination'
import { settingsApi } from '../api/settingsApi'
import type { ProviderAccount } from '../api/settingsTypes'
import { ProviderAccountsPage } from './ProviderAccountsPage'

vi.mock('../../auth/AuthSessionProvider', () => ({ useAuth: vi.fn() }))
vi.mock('../api/settingsApi', () => ({
  settingsApi: {
    listProviderAccounts: vi.fn(),
    createProviderAccount: vi.fn(),
    updateProviderAccount: vi.fn(),
  },
}))

const mockedUseAuth = vi.mocked(useAuth)
const mockedSettingsApi = vi.mocked(settingsApi)

const account: ProviderAccount = {
  id: '7', providerCode: 'AWS', displayName: 'Production AWS', externalAccountRef: 'arn:aws:123',
  status: 'ACTIVE', metadata: { region: 'ap-east-1' },
  createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-02T00:00:00Z',
}

const typedAccount: ProviderAccount = {
  id: '7', providerCode: 'AWS', displayName: 'Production AWS', externalAccountRef: 'arn:aws:123',
  status: 'ACTIVE',
  metadata: { enabled: true, retries: 3, nested: { env: 'prod' }, regions: ['sg', 'us'] },
  createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-02T00:00:00Z',
}

function renderProviderAccountsPage(permissions: string[]) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  mockedUseAuth.mockReturnValue({
    status: 'authenticated',
    user: { id: '1', email: 'admin@example.com', displayName: 'Admin', organizationId: '2', organizationMemberId: '11', permissions },
    login: vi.fn(),
    refreshMe: vi.fn(),
    logout: vi.fn(),
  } as ReturnType<typeof useAuth>)
  render(
    <QueryClientProvider client={queryClient}>
      <ProviderAccountsPage />
    </QueryClientProvider>,
  )
}

const pageOf = (items: ProviderAccount[]): PageResponse<ProviderAccount> => ({ items, page: 0, size: 50, totalElements: items.length, totalPages: items.length === 0 ? 0 : 1 })

beforeEach(() => {
  vi.clearAllMocks()
  mockedSettingsApi.listProviderAccounts.mockResolvedValue(pageOf([account]))
})

describe('ProviderAccountsPage', () => {
  it('providerAccountsCoverAllQueryStates', async () => {
    let resolveList: (value: PageResponse<ProviderAccount>) => void
    mockedSettingsApi.listProviderAccounts.mockReturnValue(new Promise((resolve) => { resolveList = resolve }))
    renderProviderAccountsPage(['PROVIDER_ACCOUNT_READ', 'PROVIDER_ACCOUNT_MANAGE'])

    expect(screen.getByText(/loading provider accounts/i)).toBeInTheDocument()
    resolveList!(pageOf([account]))
    expect(await screen.findByText('AWS')).toBeInTheDocument()
    expect(screen.getByText('Production AWS')).toBeInTheDocument()
    expect(screen.getByText('arn:aws:123')).toBeInTheDocument()
    expect(screen.getByText('active')).toBeInTheDocument()

    mockedSettingsApi.listProviderAccounts.mockRejectedValue({
      isAxiosError: true,
      response: { data: { title: 'Forbidden', status: 403, detail: 'You cannot view provider accounts.', code: 'FORBIDDEN', traceId: 't7' } },
    })
    renderProviderAccountsPage(['PROVIDER_ACCOUNT_READ'])
    expect(await screen.findByText('You cannot view provider accounts.')).toBeInTheDocument()

    mockedSettingsApi.listProviderAccounts.mockResolvedValue(pageOf([]))
    renderProviderAccountsPage(['PROVIDER_ACCOUNT_READ'])
    expect(await screen.findByText(/no provider accounts/i)).toBeInTheDocument()
  })

  it('providerFieldsMatchSchema', async () => {
    mockedSettingsApi.createProviderAccount.mockResolvedValue({ ...account, id: '9' })
    renderProviderAccountsPage(['PROVIDER_ACCOUNT_READ', 'PROVIDER_ACCOUNT_MANAGE'])

    fireEvent.click(await screen.findByRole('button', { name: 'Create provider account' }))
    fireEvent.change(await screen.findByLabelText(/provider code/i), { target: { value: 'GCP' } })
    fireEvent.change(screen.getByLabelText(/display name/i), { target: { value: 'GCP Billing' } })
    fireEvent.change(screen.getByLabelText(/external account ref/i), { target: { value: 'billing-001' } })
    fireEvent.click(screen.getByRole('button', { name: /create$/i }))

    await waitFor(() => {
      expect(mockedSettingsApi.createProviderAccount).toHaveBeenCalledTimes(1)
      expect(mockedSettingsApi.createProviderAccount).toHaveBeenCalledWith({
        providerCode: 'GCP', displayName: 'GCP Billing', externalAccountRef: 'billing-001', metadata: {},
      })
    })

    // The edit form keeps providerCode immutable.
    fireEvent.click(screen.getByRole('button', { name: 'Edit' }))
    expect(await screen.findByLabelText(/provider code/i)).toHaveValue('AWS')
    expect(screen.getByLabelText(/provider code/i)).toBeDisabled()
  })

  it('providerMetadataRejectsSecretKeysClientSide', async () => {
    renderProviderAccountsPage(['PROVIDER_ACCOUNT_READ', 'PROVIDER_ACCOUNT_MANAGE'])

    fireEvent.click(await screen.findByRole('button', { name: 'Create provider account' }))
    fireEvent.change(await screen.findByLabelText(/provider code/i), { target: { value: 'AZURE' } })
    fireEvent.change(screen.getByLabelText(/display name/i), { target: { value: 'Azure Prod' } })

    const metadataTextarea = screen.getByLabelText(/metadata json/i)
    fireEvent.change(metadataTextarea, { target: { value: '{ "access_token": "s3cr3t" }' } })
    expect(screen.getByText(/metadata keys may not contain password, token, secret or apikey/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /create$/i })).toBeDisabled()

    // The key is normalized (lowercase, non-alphanumeric removed) like the
    // backend: api_key becomes apikey and is rejected too.
    fireEvent.change(metadataTextarea, { target: { value: '{ "api_key": "s3cr3t" }' } })
    expect(screen.getByText(/metadata keys may not contain password, token, secret or apikey/i)).toBeInTheDocument()

    fireEvent.change(metadataTextarea, { target: { value: '{ "region": "s3cr3t" }' } })
    expect(screen.queryByText(/metadata keys may not contain password, token, secret or apikey/i)).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /create$/i }))
    await waitFor(() => {
      expect(mockedSettingsApi.createProviderAccount).toHaveBeenCalledWith({
        providerCode: 'AZURE', displayName: 'Azure Prod', metadata: { region: 's3cr3t' },
      })
    })
  })

  it('metadataRejectsInvalidJsonAndNonObject', async () => {
    renderProviderAccountsPage(['PROVIDER_ACCOUNT_READ', 'PROVIDER_ACCOUNT_MANAGE'])

    fireEvent.click(await screen.findByRole('button', { name: 'Create provider account' }))
    fireEvent.change(await screen.findByLabelText(/provider code/i), { target: { value: 'AZURE' } })
    fireEvent.change(screen.getByLabelText(/display name/i), { target: { value: 'Azure Prod' } })

    const metadataTextarea = screen.getByLabelText(/metadata json/i)
    fireEvent.change(metadataTextarea, { target: { value: '{ nope' } })
    expect(screen.getByText(/must be valid JSON/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /create$/i })).toBeDisabled()

    fireEvent.change(metadataTextarea, { target: { value: '[1, 2]' } })
    expect(screen.getByText(/must be a JSON object/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /create$/i })).toBeDisabled()
  })

  it('metadataOmittedFromUpdateWhenUnchanged', async () => {
    mockedSettingsApi.listProviderAccounts.mockResolvedValue(pageOf([typedAccount]))
    renderProviderAccountsPage(['PROVIDER_ACCOUNT_READ', 'PROVIDER_ACCOUNT_MANAGE'])

    fireEvent.click(await screen.findByRole('button', { name: 'Edit' }))
    fireEvent.change(await screen.findByLabelText(/display name/i), { target: { value: 'Renamed AWS' } })
    fireEvent.click(screen.getByRole('button', { name: /save/i }))

    await waitFor(() => {
      expect(mockedSettingsApi.updateProviderAccount).toHaveBeenCalledTimes(1)
      expect(mockedSettingsApi.updateProviderAccount).toHaveBeenCalledWith('7', {
        displayName: 'Renamed AWS', externalAccountRef: 'arn:aws:123', status: 'ACTIVE',
      })
    })
  })

  it('metadataEditSendsParsedTypedJson', async () => {
    mockedSettingsApi.listProviderAccounts.mockResolvedValue(pageOf([typedAccount]))
    renderProviderAccountsPage(['PROVIDER_ACCOUNT_READ', 'PROVIDER_ACCOUNT_MANAGE'])

    fireEvent.click(await screen.findByRole('button', { name: 'Edit' }))
    fireEvent.change(await screen.findByLabelText(/metadata json/i), {
      target: { value: JSON.stringify({ enabled: false, retries: 5, nested: { env: 'staging' }, regions: ['sg', 'us', 'jp'] }, null, 2) },
    })
    fireEvent.click(screen.getByRole('button', { name: /save/i }))

    await waitFor(() => {
      expect(mockedSettingsApi.updateProviderAccount).toHaveBeenCalledWith('7', expect.objectContaining({
        metadata: { enabled: false, retries: 5, nested: { env: 'staging' }, regions: ['sg', 'us', 'jp'] },
      }))
    })
  })

  it('providerMutationInvalidatesQueries', async () => {
    mockedSettingsApi.createProviderAccount.mockResolvedValue({ ...account, id: '9' })
    renderProviderAccountsPage(['PROVIDER_ACCOUNT_READ', 'PROVIDER_ACCOUNT_MANAGE'])

    fireEvent.click(await screen.findByRole('button', { name: 'Create provider account' }))
    fireEvent.change(await screen.findByLabelText(/provider code/i), { target: { value: 'GCP' } })
    fireEvent.change(screen.getByLabelText(/display name/i), { target: { value: 'GCP Billing' } })
    fireEvent.click(screen.getByRole('button', { name: /create$/i }))

    await waitFor(() => {
      expect(mockedSettingsApi.listProviderAccounts).toHaveBeenCalledTimes(2)
    })
  })
})
