import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from '../../auth/authApi'
import { periodCloseApi } from './periodCloseApi'

vi.mock('../../auth/authApi', () => ({
  apiClient: { get: vi.fn(), post: vi.fn() },
}))

const mockedGet = vi.mocked(apiClient.get)
const mockedPost = vi.mocked(apiClient.post)

beforeEach(() => vi.clearAllMocks())

describe('periodCloseApi', () => {
  it('loads readiness and close history using the OpenAPI paths', async () => {
    mockedGet.mockResolvedValue({ data: { items: [] } })
    await periodCloseApi.getReadiness('11')
    await periodCloseApi.listCloseRuns('11', { page: 0, size: 20 })
    expect(mockedGet).toHaveBeenNthCalledWith(1, '/billing-periods/11/close-readiness')
    expect(mockedGet).toHaveBeenNthCalledWith(2, '/billing-periods/11/close-runs', { params: { page: 0, size: 20 } })
  })

  it('sends idempotency keys for close and reopen', async () => {
    mockedPost.mockResolvedValue({ data: { runId: '9' } })
    await periodCloseApi.close('11', 'close-key')
    await periodCloseApi.reopen('11', { reasonCode: 'REVIEW', reasonNote: 'note' }, 'reopen-key')
    expect(mockedPost.mock.calls.map((call) => call[2])).toEqual([
      { headers: { 'Idempotency-Key': 'close-key' } },
      { headers: { 'Idempotency-Key': 'reopen-key' } },
    ])
  })
})
