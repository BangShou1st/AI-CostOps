import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from '../auth/AuthSessionProvider'
import type { PageResponse } from '../../api/pagination'
import { importsApi } from './api/importsApi'
import { settingsApi } from '../settings/api/settingsApi'
import type { ImportSummary } from './api/importTypes'
import { ImportDetailPage } from './ImportDetailPage'
import { ImportListPage } from './ImportListPage'

vi.mock('../auth/AuthSessionProvider', () => ({ useAuth: vi.fn() }))
vi.mock('./api/importsApi', () => ({
  importsApi: {
    listImports: vi.fn(),
    getImport: vi.fn(),
    listEvidenceImports: vi.fn(),
    listAttempts: vi.fn(),
    listIssues: vi.fn(),
    listRawRecords: vi.fn(),
    getRawRecord: vi.fn(),
    retry: vi.fn(),
    cancel: vi.fn(),
    uploadProviderImport: vi.fn(),
  },
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
vi.mock('./upload/ProviderImportUploadModal', () => ({
  ProviderImportUploadModal: ({ open, onUploaded }: { open: boolean; onUploaded: (r: unknown) => void }) =>
    open ? (
      <button
        onClick={() => onUploaded({
          evidenceId: '1', importBatchId: '77', latestAttemptId: '3', batchStatus: 'PENDING',
          duplicateEvidence: false, duplicateBatch: true,
        })}
      >
        模拟上传完成
      </button>
    ) : null,
}))

const navigateMock = vi.fn()
const mockedUseAuth = vi.mocked(useAuth)
const mockedImportsApi = vi.mocked(importsApi)
const mockedSettingsApi = vi.mocked(settingsApi)

const importSummary: ImportSummary = {
  id: '123',
  evidence: { id: '9', originalFilename: 'invoice.csv' },
  providerAccount: { id: '5', displayName: 'Primary' },
  expectedProviderCode: 'TEST_PROVIDER',
  sourceType: 'FILE_EXPORT',
  parserVersion: 'test-parser-v1',
  status: 'FAILED',
  periodStart: null,
  periodEnd: null,
  latestAttempt: {
    id: '3', attemptNo: 1, status: 'FAILED', triggerType: 'INITIAL', predecessorAttemptId: null,
    parserVersion: 'test-parser-v1', detectedProviderCode: null, schemaFingerprint: null,
    startedAt: null, finishedAt: '2026-08-01T00:00:00Z', createdAt: '2026-08-01T00:00:00Z',
    recordsSeen: 0, recordsValid: 0, warningCount: 0, errorCount: 1,
    errorCode: 'ERR', errorSummary: 'summary',
  },
  createdByMemberId: '9',
  createdAt: '2026-08-01T00:00:00Z',
  updatedAt: '2026-08-01T00:00:00Z',
  retryable: true,
  cancelable: false,
}

function queryClientForTest() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } })
}

function renderPage(permissions: string[], page: 'list' | 'detail') {
  mockedUseAuth.mockReturnValue({
    status: 'authenticated',
    user: { id: '1', email: 'admin@example.com', displayName: 'Admin', organizationId: '2', organizationMemberId: '3', permissions },
    login: vi.fn(),
    refreshMe: vi.fn(),
    logout: vi.fn(),
  } as ReturnType<typeof useAuth>)
  return render(
    <QueryClientProvider client={queryClientForTest()}>
      {page === 'list' ? <ImportListPage /> : <ImportDetailPage importId="123" />}
    </QueryClientProvider>,
  )
}

const pageOf = <T,>(items: T[], total = items.length): PageResponse<T> =>
  ({ items, page: 0, size: 50, totalElements: total, totalPages: 1 })

beforeEach(() => {
  vi.clearAllMocks()
  navigateMock.mockReset()
  mockedImportsApi.listImports.mockResolvedValue(pageOf([importSummary]))
  mockedImportsApi.getImport.mockResolvedValue(importSummary)
  mockedImportsApi.listAttempts.mockResolvedValue(pageOf([]))
  mockedImportsApi.listIssues.mockResolvedValue(pageOf([]))
  mockedImportsApi.listRawRecords.mockResolvedValue(pageOf([]))
  mockedSettingsApi.listProviderAccounts.mockResolvedValue(pageOf([]))
})

afterEach(() => {
  vi.useRealTimers()
})

describe('ImportListPage', () => {
  it('renders import rows and navigates on row click', async () => {
    renderPage(['IMPORT_READ'], 'list')

    expect(await screen.findByText('invoice.csv')).toBeInTheDocument()
    fireEvent.click(screen.getByText('invoice.csv'))
    await waitFor(() => expect(navigateMock).toHaveBeenCalledWith('/imports/123'))
  })

  it('does not mount provider-account filter query without provider-account read', async () => {
    renderPage(['IMPORT_READ'], 'list')
    await screen.findByText('invoice.csv')

    expect(mockedSettingsApi.listProviderAccounts).not.toHaveBeenCalled()
  })

  it('mounts provider-account filter options only with provider-account read', async () => {
    mockedSettingsApi.listProviderAccounts.mockResolvedValue(pageOf([
      {
        id: '5', providerCode: 'TEST_PROVIDER', displayName: 'Primary', externalAccountRef: null,
        status: 'ACTIVE', metadata: {}, createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-01T00:00:00Z',
      },
    ]))
    renderPage(['IMPORT_READ', 'PROVIDER_ACCOUNT_READ'], 'list')
    await screen.findByText('invoice.csv')

    expect(mockedSettingsApi.listProviderAccounts).toHaveBeenCalled()
    fireEvent.mouseDown(screen.getByLabelText(/Provider 账号/))
    const matches = await screen.findAllByText('Primary')
    expect(matches.length).toBeGreaterThan(1)
  })

  it('shows upload button only with upload and provider-account read permissions', async () => {
    renderPage(['IMPORT_READ'], 'list')
    await screen.findByText('invoice.csv')
    expect(screen.queryByRole('button', { name: /上传/ })).not.toBeInTheDocument()

    renderPage(['IMPORT_READ', 'EVIDENCE_UPLOAD_PROVIDER', 'PROVIDER_ACCOUNT_READ'], 'list')
    expect(await screen.findByRole('button', { name: /上传/ })).toBeInTheDocument()
  })

  it('upload success navigates to import detail', async () => {
    renderPage(['IMPORT_READ', 'EVIDENCE_UPLOAD_PROVIDER', 'PROVIDER_ACCOUNT_READ'], 'list')
    await screen.findByRole('button', { name: /上传/ })

    fireEvent.click(screen.getByRole('button', { name: /上传/ }))
    fireEvent.click(await screen.findByRole('button', { name: '模拟上传完成' }))

    await waitFor(() => expect(navigateMock).toHaveBeenCalledWith('/imports/77'))
  })
})

describe('ImportDetailPage', () => {
  it('renders overview fields and no M3 tabs or actions', async () => {
    renderPage(['IMPORT_READ'], 'detail')

    expect(await screen.findByText('invoice.csv')).toBeInTheDocument()
    expect(screen.getByText('Primary')).toBeInTheDocument()
    expect(screen.getByText('FILE_EXPORT')).toBeInTheDocument()
    expect(screen.queryByText(/归一化事实|确认导入|READY_FOR_REVIEW|分配建议|总账/i)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /确\s*认/ })).not.toBeInTheDocument()
  })

  it('shows retry button only with retry permission and retryable state', async () => {
    renderPage(['IMPORT_READ'], 'detail')
    await screen.findByText('invoice.csv')
    expect(screen.queryByRole('button', { name: /重\s*试/ })).not.toBeInTheDocument()

    renderPage(['IMPORT_READ', 'IMPORT_RETRY'], 'detail')
    expect(await screen.findByRole('button', { name: /重\s*试/ })).toBeInTheDocument()
  })

  it('retry success restarts polling and invalidates caches', async () => {
    vi.spyOn(crypto, 'randomUUID').mockReturnValue('uuid-retry-1')
    mockedImportsApi.retry.mockResolvedValue({ ...importSummary, status: 'PENDING', retryable: false })

    renderPage(['IMPORT_READ', 'IMPORT_RETRY'], 'detail')
    await screen.findByRole('button', { name: /重\s*试/ })
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }))

    await waitFor(() => {
      expect(mockedImportsApi.retry).toHaveBeenCalledWith('123', 'uuid-retry-1')
    })
    // Detail refetched after invalidation.
    await waitFor(() => {
      expect(mockedImportsApi.getImport).toHaveBeenCalledTimes(2)
    })
  })

  it('cancel success stops polling', async () => {
    vi.spyOn(crypto, 'randomUUID').mockReturnValue('uuid-cancel-1')
    const processing: ImportSummary = { ...importSummary, status: 'PROCESSING', retryable: false, cancelable: true }
    mockedImportsApi.getImport.mockResolvedValue(processing)
    mockedImportsApi.cancel.mockResolvedValue({ ...processing, status: 'CANCELED', cancelable: false, retryable: true })

    renderPage(['IMPORT_READ', 'IMPORT_CANCEL'], 'detail')
    await screen.findByRole('button', { name: /取\s*消/ })
    fireEvent.click(screen.getByRole('button', { name: /取\s*消/ }))

    await waitFor(() => {
      expect(mockedImportsApi.cancel).toHaveBeenCalledWith('123', 'uuid-cancel-1')
    })
    await waitFor(() => {
      expect(mockedImportsApi.getImport).toHaveBeenCalledTimes(2)
    })
  })

  it('409 conflict shows state-changed error and never auto-replays the mutation', async () => {
    vi.spyOn(crypto, 'randomUUID').mockReturnValue('uuid-conflict-1')
    mockedImportsApi.retry.mockRejectedValue({
      isAxiosError: true,
      response: { data: {
        title: 'State conflict', status: 409, detail: 'Import cannot be retried.',
        code: 'STATE_CONFLICT', traceId: 't1',
      } },
    })

    renderPage(['IMPORT_READ', 'IMPORT_RETRY'], 'detail')
    await screen.findByRole('button', { name: /重\s*试/ })
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }))

    expect(await screen.findByText('Import cannot be retried.')).toBeInTheDocument()
    await waitFor(() => {
      // Detail invalidated (fresh state), but no second mutation with a new key.
      expect(mockedImportsApi.getImport).toHaveBeenCalledTimes(2)
    })
    expect(mockedImportsApi.retry).toHaveBeenCalledTimes(1)
  })

  it('polls detail every three seconds while active and stops on terminal status', async () => {
    vi.useFakeTimers()
    const active: ImportSummary = { ...importSummary, status: 'PENDING', retryable: false, cancelable: true }
    mockedImportsApi.getImport.mockResolvedValue(active)

    renderPage(['IMPORT_READ'], 'detail')
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(screen.getByText('invoice.csv')).toBeInTheDocument()
    expect(mockedImportsApi.getImport).toHaveBeenCalledTimes(1)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(3000)
    })
    expect(mockedImportsApi.getImport).toHaveBeenCalledTimes(2)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(3000)
    })
    expect(mockedImportsApi.getImport).toHaveBeenCalledTimes(3)

    // Terminal status stops the interval.
    mockedImportsApi.getImport.mockResolvedValue({ ...active, status: 'PARSED', cancelable: false })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(3000)
    })
    const callsAfterTerminal = mockedImportsApi.getImport.mock.calls.length
    await act(async () => {
      await vi.advanceTimersByTimeAsync(9000)
    })
    expect(mockedImportsApi.getImport.mock.calls.length).toBe(callsAfterTerminal)
  })

  it('unmount stops polling', async () => {
    vi.useFakeTimers()
    const active: ImportSummary = { ...importSummary, status: 'PROCESSING', retryable: false, cancelable: true }
    mockedImportsApi.getImport.mockResolvedValue(active)

    const { unmount } = renderPage(['IMPORT_READ'], 'detail')
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(screen.getByText('invoice.csv')).toBeInTheDocument()
    unmount()

    await act(async () => {
      await vi.advanceTimersByTimeAsync(9000)
    })
    expect(mockedImportsApi.getImport).toHaveBeenCalledTimes(1)
  })
})
