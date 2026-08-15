import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from '../../auth/authApi'
import { importsApi } from './importsApi'

vi.mock('../../auth/authApi', () => ({
  apiClient: {
    get: vi.fn(),
    post: vi.fn(),
  },
}))

const mockedGet = vi.mocked(apiClient.get)
const mockedPost = vi.mocked(apiClient.post)

const pageOf = <T>(items: T[], total = items.length): { data: unknown } => ({
  data: { items, page: 0, size: 50, totalElements: total, totalPages: 1 },
})

beforeEach(() => {
  vi.clearAllMocks()
})

describe('importsApi', () => {
  it('lists imports with page size and optional filters', async () => {
    mockedGet.mockResolvedValue(pageOf([]))

    await importsApi.listImports({ page: 2, size: 25, status: 'FAILED', providerAccountId: '7' })

    expect(mockedGet).toHaveBeenCalledWith('/imports', {
      params: { page: 2, size: 25, status: 'FAILED', providerAccountId: '7' },
    })
  })

  it('lists imports without undefined filters', async () => {
    mockedGet.mockResolvedValue(pageOf([]))

    await importsApi.listImports({ page: 0, size: 50 })

    expect(mockedGet).toHaveBeenCalledWith('/imports', { params: { page: 0, size: 50 } })
  })

  it('gets import detail by id', async () => {
    mockedGet.mockResolvedValue({ data: { id: '123' } })

    await importsApi.getImport('123')

    expect(mockedGet).toHaveBeenCalledWith('/imports/123')
  })

  it('lists evidence imports for an evidence id', async () => {
    mockedGet.mockResolvedValue(pageOf([]))

    await importsApi.listEvidenceImports('42', 1, 10)

    expect(mockedGet).toHaveBeenCalledWith('/evidence/42/imports', { params: { page: 1, size: 10 } })
  })

  it('lists attempts with page and size', async () => {
    mockedGet.mockResolvedValue(pageOf([]))

    await importsApi.listAttempts('123', 0, 50)

    expect(mockedGet).toHaveBeenCalledWith('/imports/123/attempts', { params: { page: 0, size: 50 } })
  })

  it('lists issues with severity and code filters', async () => {
    mockedGet.mockResolvedValue(pageOf([]))

    await importsApi.listIssues('123', '9', { page: 0, size: 50, severity: 'WARN', issueCode: 'FIELD_EMPTY' })

    expect(mockedGet).toHaveBeenCalledWith('/imports/123/attempts/9/issues', {
      params: { page: 0, size: 50, severity: 'WARN', issueCode: 'FIELD_EMPTY' },
    })
  })

  it('lists raw records with normalize status filter', async () => {
    mockedGet.mockResolvedValue(pageOf([]))

    await importsApi.listRawRecords('123', '9', { page: 1, size: 25, normalizeStatus: 'ERROR' })

    expect(mockedGet).toHaveBeenCalledWith('/imports/123/attempts/9/raw-records', {
      params: { page: 1, size: 25, normalizeStatus: 'ERROR' },
    })
  })

  it('gets one raw record detail', async () => {
    mockedGet.mockResolvedValue({ data: { id: '5' } })

    await importsApi.getRawRecord('123', '9', '5')

    expect(mockedGet).toHaveBeenCalledWith('/imports/123/attempts/9/raw-records/5')
  })

  it('retries with the idempotency key header and no body', async () => {
    mockedPost.mockResolvedValue({ data: { id: '123' } })

    await importsApi.retry('123', 'idem-1')

    expect(mockedPost).toHaveBeenCalledWith('/imports/123/retry', undefined, {
      headers: { 'Idempotency-Key': 'idem-1' },
    })
  })

  it('cancels with the idempotency key header and no body', async () => {
    mockedPost.mockResolvedValue({ data: { id: '123' } })

    await importsApi.cancel('123', 'idem-2')

    expect(mockedPost).toHaveBeenCalledWith('/imports/123/cancel', undefined, {
      headers: { 'Idempotency-Key': 'idem-2' },
    })
  })

  it('uploads provider import as multipart fields', async () => {
    mockedPost.mockResolvedValue({
      data: { evidenceId: '1', importBatchId: '2', latestAttemptId: '3', batchStatus: 'PENDING' },
    })
    const file = new File(['bytes'], 'invoice.csv', { type: 'text/csv' })

    await importsApi.uploadProviderImport({ file, providerAccountId: '7', sourceType: 'FILE_EXPORT' })

    const [url, body, config] = mockedPost.mock.calls[0]
    expect(url).toBe('/provider-imports')
    expect(body).toBeInstanceOf(FormData)
    const form = body as FormData
    expect(form.get('file')).toBe(file)
    expect(form.get('providerAccountId')).toBe('7')
    expect(form.get('sourceType')).toBe('FILE_EXPORT')
    expect(config).toEqual({ headers: { 'Content-Type': 'multipart/form-data' } })
  })
})
