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
})