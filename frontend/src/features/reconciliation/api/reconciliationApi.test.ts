import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from '../../auth/authApi'
import { reconciliationApi } from './reconciliationApi'

vi.mock('../../auth/authApi', () => ({
  apiClient: { get: vi.fn(), post: vi.fn() },
}))

const mockedGet = vi.mocked(apiClient.get)
const mockedPost = vi.mocked(apiClient.post)

beforeEach(() => vi.clearAllMocks())

describe('reconciliationApi', () => {
  it('lists runs with the required billing period and pagination', async () => {
    mockedGet.mockResolvedValue({ data: { items: [], page: 0, size: 30, totalElements: 0, totalPages: 0 } })
    await reconciliationApi.listRuns({ billingPeriodId: '42', page: 0, size: 30 })
    expect(mockedGet).toHaveBeenCalledWith('/reconciliation-runs', { params: { billingPeriodId: '42', page: 0, size: 30 } })
  })

  it('sends idempotency keys on every reconciliation mutation', async () => {
    mockedPost.mockResolvedValue({ data: { id: '7' } })
    await reconciliationApi.createRun({ billingPeriodId: '42' }, 'run-key')
    await reconciliationApi.investigateCase('7', 'investigate-key')
    await reconciliationApi.returnCaseToOpen('7', 'return-key')
    await reconciliationApi.resolveCase('7', { reasonCode: 'FIXED', resolutionNote: 'note' }, 'resolve-key')
    expect(mockedPost.mock.calls.map((call) => call[2])).toEqual([
      { headers: { 'Idempotency-Key': 'run-key' } },
      { headers: { 'Idempotency-Key': 'investigate-key' } },
      { headers: { 'Idempotency-Key': 'return-key' } },
      { headers: { 'Idempotency-Key': 'resolve-key' } },
    ])
  })
})
