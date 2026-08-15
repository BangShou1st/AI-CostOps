import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from '../auth/AuthSessionProvider'
import type { PageResponse } from '../../api/pagination'
import { evidenceApi } from './api/evidenceApi'
import { importsApi } from '../imports/api/importsApi'
import { settingsApi } from '../settings/api/settingsApi'
import { EvidenceDetailPage } from './EvidenceDetailPage'
import { EvidenceListPage } from './EvidenceListPage'

vi.mock('../auth/AuthSessionProvider', () => ({ useAuth: vi.fn() }))
vi.mock('./api/evidenceApi', () => ({
  evidenceApi: { listEvidence: vi.fn(), getEvidence: vi.fn() },
}))
vi.mock('../imports/api/importsApi', () => ({
  importsApi: { listEvidenceImports: vi.fn() },
}))
vi.mock('../settings/api/settingsApi', () => ({
  settingsApi: { listProviderAccounts: vi.fn() },
}))
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>()
  return {
    ...actual,
    useNavigate: () => navigateMock,
  }
})
vi.mock('../imports/upload/ProviderImportUploadModal', () => ({
  ProviderImportUploadModal: ({ open, onUploaded }: { open: boolean; onUploaded: (r: unknown) => void }) =>
    open ? (
      <div>
        <button
          onClick={() => onUploaded({
            evidenceId: '1', importBatchId: '77', latestAttemptId: '3', batchStatus: 'PENDING',
            duplicateEvidence: true, duplicateBatch: true,
          })}
        >
          模拟上传完成
        </button>
      </div>
    ) : null,
}))

const navigateMock = vi.fn()
const mockedUseAuth = vi.mocked(useAuth)
const mockedEvidenceApi = vi.mocked(evidenceApi)
const mockedImportsApi = vi.mocked(importsApi)
const mockedSettingsApi = vi.mocked(settingsApi)

const evidence = {
  id: '9007199254740993',
  originalFilename: 'invoice.csv',
  mediaType: 'text/csv',
  sizeBytes: 1024,
  sha256: 'a'.repeat(64),
  storageStatus: 'AVAILABLE',
  storageErrorCode: null,
  uploadedByMemberId: '9',
  createdAt: '2026-08-01T00:00:00Z',
  updatedAt: '2026-08-01T00:00:00Z',
}
const importSummary = {
  id: '77',
  evidence: { id: '9007199254740993', originalFilename: 'invoice.csv' },
  providerAccount: { id: '5', displayName: 'Primary' },
  expectedProviderCode: 'TEST_PROVIDER',
  sourceType: 'FILE_EXPORT',
  parserVersion: 'test-parser-v1',
  status: 'PENDING',
  periodStart: null,
  periodEnd: null,
  latestAttempt: { id: '3', attemptNo: 1, status: 'QUEUED' },
  createdByMemberId: '9',
  createdAt: '2026-08-01T00:00:00Z',
  updatedAt: '2026-08-01T00:00:00Z',
  retryable: false,
  cancelable: true,
}
const pageOf = <T,>(items: T[], total = items.length): PageResponse<T> =>
  ({ items, page: 0, size: 50, totalElements: total, totalPages: 1 })

function renderPage(permissions: string[], page: 'list' | 'detail' = 'list') {
  cleanup()
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  mockedUseAuth.mockReturnValue({
    status: 'authenticated',
    user: { id: '1', email: 'admin@example.com', displayName: 'Admin', organizationId: '2', organizationMemberId: '3', permissions },
    login: vi.fn(),
    refreshMe: vi.fn(),
    logout: vi.fn(),
  } as ReturnType<typeof useAuth>)
  return render(
    <QueryClientProvider client={queryClient}>
      {page === 'list' ? <EvidenceListPage /> : <EvidenceDetailPage evidenceId="9007199254740993" />}
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  navigateMock.mockReset()
  mockedEvidenceApi.listEvidence.mockResolvedValue(pageOf([evidence]))
  mockedEvidenceApi.getEvidence.mockResolvedValue(evidence)
  mockedImportsApi.listEvidenceImports.mockResolvedValue(pageOf([importSummary]))
  mockedSettingsApi.listProviderAccounts.mockResolvedValue(pageOf([]))
})

describe('EvidenceListPage', () => {
  it('paginates through the server', async () => {
    mockedEvidenceApi.listEvidence.mockResolvedValue({
      items: [evidence], page: 0, size: 50, totalElements: 55, totalPages: 2,
    })
    renderPage(['EVIDENCE_READ'])
    await screen.findByText('invoice.csv')

    fireEvent.click(screen.getByTitle('2'))

    await waitFor(() => {
      expect(mockedEvidenceApi.listEvidence).toHaveBeenLastCalledWith(1, 50)
    })
  })

  it('shows upload action only with upload and provider-account read permissions', async () => {
    renderPage(['EVIDENCE_READ', 'EVIDENCE_UPLOAD_PROVIDER'])
    await screen.findByText('invoice.csv')
    expect(screen.queryByRole('button', { name: /上传/ })).not.toBeInTheDocument()
    expect(mockedSettingsApi.listProviderAccounts).not.toHaveBeenCalled()

    renderPage(['EVIDENCE_READ', 'PROVIDER_ACCOUNT_READ'])
    await screen.findByText('invoice.csv')
    expect(screen.queryByRole('button', { name: /上传/ })).not.toBeInTheDocument()

    renderPage(['EVIDENCE_READ', 'EVIDENCE_UPLOAD_PROVIDER', 'PROVIDER_ACCOUNT_READ'])
    await screen.findByText('invoice.csv')
    expect(screen.getByRole('button', { name: /上传/ })).toBeInTheDocument()
  })

  it('upload success navigates to import detail and surfaces duplicate flags', async () => {
    renderPage(['EVIDENCE_READ', 'EVIDENCE_UPLOAD_PROVIDER', 'PROVIDER_ACCOUNT_READ'])
    await screen.findByText('invoice.csv')

    fireEvent.click(screen.getByRole('button', { name: /上传/ }))
    fireEvent.click(await screen.findByRole('button', { name: '模拟上传完成' }))

    await waitFor(() => {
      expect(navigateMock).toHaveBeenCalledWith('/imports/77')
    })
    expect(await screen.findByText(/已复用现有/)).toBeInTheDocument()
  })
})

describe('EvidenceDetailPage', () => {
  it('renders metadata without any object key', async () => {
    renderPage(['EVIDENCE_READ'], 'detail')

    expect(await screen.findByText('invoice.csv')).toBeInTheDocument()
    expect(screen.getByText('a'.repeat(64))).toBeInTheDocument()
    expect(screen.queryByText(/objectKey|object_key|org\//i)).not.toBeInTheDocument()
  })

  it('shows download action only with download permission', async () => {
    renderPage(['EVIDENCE_READ'], 'detail')
    await screen.findByText('invoice.csv')
    expect(screen.queryByRole('button', { name: /下载/ })).not.toBeInTheDocument()

    renderPage(['EVIDENCE_READ', 'EVIDENCE_DOWNLOAD'], 'detail')
    expect(await screen.findByRole('button', { name: /下\s*载/ })).toBeInTheDocument()
  })

  it('starts associated imports query only with import read permission', async () => {
    renderPage(['EVIDENCE_READ'], 'detail')
    await screen.findByText('invoice.csv')
    expect(mockedImportsApi.listEvidenceImports).not.toHaveBeenCalled()

    renderPage(['EVIDENCE_READ', 'IMPORT_READ'], 'detail')
    expect(await screen.findByText('Primary')).toBeInTheDocument()
    expect(mockedImportsApi.listEvidenceImports).toHaveBeenCalledWith('9007199254740993', 0, 50)
  })
})
