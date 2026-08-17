import { describe, it, expect, vi, beforeEach } from 'vitest'
import { apiClient } from '../../auth/authApi'
import { expenseApi } from './expenseApi'

vi.mock('../../auth/authApi', () => ({
  apiClient: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    defaults: { baseURL: 'http://localhost/api/v1' },
  },
}))

const mockedApiClient = vi.mocked(apiClient)

beforeEach(() => {
  vi.clearAllMocks()
})

describe('expenseApi', () => {
  it('downloadEvidence fetches an authenticated blob through apiClient', async () => {
    const blob = new Blob(['receipt-bytes'], { type: 'application/pdf' })
    mockedApiClient.get.mockResolvedValue({ data: blob })

    const result = await expenseApi.downloadEvidence('exp-1')

    expect(mockedApiClient.get).toHaveBeenCalledWith(
      '/expenses/exp-1/evidence/download',
      { responseType: 'blob' },
    )
    expect(result).toBe(blob)
  })

  it('downloadEvidence encodes the expense id and relies on the interceptor for auth', async () => {
    const blob = new Blob([])
    mockedApiClient.get.mockResolvedValue({ data: blob })

    await expenseApi.downloadEvidence('exp/with+special')

    expect(mockedApiClient.get).toHaveBeenCalledWith(
      '/expenses/exp%2Fwith%2Bspecial/evidence/download',
      { responseType: 'blob' },
    )
  })

  it('no longer exposes a naked evidenceDownloadUrl that bypasses the session', () => {
    expect((expenseApi as unknown as Record<string, unknown>).evidenceDownloadUrl).toBeUndefined()
  })

  it('create, submit, and cancel carry the Idempotency-Key header on the request', async () => {
    mockedApiClient.post.mockResolvedValue({ data: {} })

    await expenseApi.create({ expenseDate: '2026-08-01', amount: '10.00000000', currency: 'CNY' }, 'key-create')
    expect(mockedApiClient.post).toHaveBeenLastCalledWith('/expenses', expect.any(Object), {
      headers: { 'Idempotency-Key': 'key-create' },
    })

    await expenseApi.submit('exp-1', { expectedVersion: 1 }, 'key-submit')
    expect(mockedApiClient.post).toHaveBeenLastCalledWith('/expenses/exp-1/submit', { expectedVersion: 1 }, {
      headers: { 'Idempotency-Key': 'key-submit' },
    })

    await expenseApi.cancel('exp-1', { expectedVersion: 1 }, 'key-cancel')
    expect(mockedApiClient.post).toHaveBeenLastCalledWith('/expenses/exp-1/cancel', { expectedVersion: 1 }, {
      headers: { 'Idempotency-Key': 'key-cancel' },
    })
  })
})