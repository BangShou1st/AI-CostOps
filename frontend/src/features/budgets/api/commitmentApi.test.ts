import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from '../../auth/authApi'
import { commitmentApi } from './commitmentApi'

vi.mock('../../auth/authApi', () => ({
  apiClient: {
    get: vi.fn(),
    post: vi.fn(),
  },
}))

const mockedGet = vi.mocked(apiClient.get)
const mockedPost = vi.mocked(apiClient.post)

const pageOf = <T>(items: T[]): { data: unknown } => ({
  data: { items, page: 0, size: 50, totalElements: items.length, totalPages: 1 },
})

beforeEach(() => {
  vi.clearAllMocks()
})

describe('commitmentApi', () => {
  it('lists commitments with budgetId filter and pagination', async () => {
    mockedGet.mockResolvedValue(pageOf([]))

    await commitmentApi.list({ page: 2, size: 25, budgetId: '7' })

    expect(mockedGet).toHaveBeenCalledWith('/commitments', {
      params: { page: 2, size: 25, budgetId: '7' },
    })
  })

  it('lists commitments without undefined filters', async () => {
    mockedGet.mockResolvedValue(pageOf([]))

    await commitmentApi.list({ page: 0, size: 50 })

    expect(mockedGet).toHaveBeenCalledWith('/commitments', { params: { page: 0, size: 50 } })
  })

  it('gets commitment detail by id', async () => {
    mockedGet.mockResolvedValue({ data: { id: '9' } })

    await commitmentApi.get('9')

    expect(mockedGet).toHaveBeenCalledWith('/commitments/9')
  })

  it('creates a commitment on the budget with amount, currency and Idempotency-Key', async () => {
    mockedPost.mockResolvedValue({ data: { id: '9' } })

    await commitmentApi.create('7', { requestedAmount: '50.00000000', currency: 'CNY' }, 'idem-1')

    expect(mockedPost).toHaveBeenCalledWith('/budgets/7/commitments', {
      requestedAmount: '50.00000000',
      currency: 'CNY',
    }, {
      headers: { 'Idempotency-Key': 'idem-1' },
    })
  })

  it('approves with expectedVersion and Idempotency-Key', async () => {
    mockedPost.mockResolvedValue({ data: { id: '9' } })

    await commitmentApi.approve('9', { expectedVersion: 3 }, 'idem-2')

    expect(mockedPost).toHaveBeenCalledWith('/commitments/9/approve', { expectedVersion: 3 }, {
      headers: { 'Idempotency-Key': 'idem-2' },
    })
  })

  it('rejects with expectedVersion, optional comment and Idempotency-Key', async () => {
    mockedPost.mockResolvedValue({ data: { id: '9' } })

    await commitmentApi.reject('9', { expectedVersion: 3, comment: 'duplicate' }, 'idem-3')

    expect(mockedPost).toHaveBeenCalledWith('/commitments/9/reject', {
      expectedVersion: 3,
      comment: 'duplicate',
    }, {
      headers: { 'Idempotency-Key': 'idem-3' },
    })
  })

  it('reject works without a comment', async () => {
    mockedPost.mockResolvedValue({ data: { id: '9' } })

    await commitmentApi.reject('9', { expectedVersion: 4 }, 'idem-4')

    expect(mockedPost).toHaveBeenCalledWith('/commitments/9/reject', { expectedVersion: 4 }, {
      headers: { 'Idempotency-Key': 'idem-4' },
    })
  })

  it('cancels with expectedVersion and Idempotency-Key', async () => {
    mockedPost.mockResolvedValue({ data: { id: '9' } })

    await commitmentApi.cancel('9', { expectedVersion: 3 }, 'idem-5')

    expect(mockedPost).toHaveBeenCalledWith('/commitments/9/cancel', { expectedVersion: 3 }, {
      headers: { 'Idempotency-Key': 'idem-5' },
    })
  })

  it('releases with expectedVersion and Idempotency-Key', async () => {
    mockedPost.mockResolvedValue({ data: { id: '9' } })

    await commitmentApi.release('9', { expectedVersion: 5 }, 'idem-6')

    expect(mockedPost).toHaveBeenCalledWith('/commitments/9/release', { expectedVersion: 5 }, {
      headers: { 'Idempotency-Key': 'idem-6' },
    })
  })
})
