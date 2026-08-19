import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from '../../auth/authApi'
import { budgetApi } from './budgetApi'

vi.mock('../../auth/authApi', () => ({
  apiClient: {
    get: vi.fn(),
    put: vi.fn(),
  },
}))

const mockedGet = vi.mocked(apiClient.get)
const mockedPut = vi.mocked(apiClient.put)

const pageOf = <T>(items: T[]): { data: unknown } => ({
  data: { items, page: 0, size: 50, totalElements: items.length, totalPages: 1 },
})

beforeEach(() => {
  vi.clearAllMocks()
})

describe('budgetApi', () => {
  it('lists budgets with page, size and optional scope filters', async () => {
    mockedGet.mockResolvedValue(pageOf([]))

    await budgetApi.list({ page: 1, size: 25, scopeType: 'PROJECT', scopeId: '42' })

    expect(mockedGet).toHaveBeenCalledWith('/budgets', {
      params: { page: 1, size: 25, scopeType: 'PROJECT', scopeId: '42' },
    })
  })

  it('lists budgets without undefined filters', async () => {
    mockedGet.mockResolvedValue(pageOf([]))

    await budgetApi.list({ page: 0, size: 50 })

    expect(mockedGet).toHaveBeenCalledWith('/budgets', { params: { page: 0, size: 50 } })
  })

  it('gets budget detail by id', async () => {
    mockedGet.mockResolvedValue({
      data: {
        id: '7',
        billingPeriodId: '3',
        scopeType: 'ORG',
        scopeId: '1',
        currency: 'CNY',
        totalAmount: '100.00000000',
        actualAmount: '30.00000000',
        committedAmount: '20.00000000',
        availableAmount: '48.50000000',
        overBudget: false,
        status: 'ACTIVE',
        version: 4,
        createdAt: '2026-01-01T00:00:00Z',
        updatedAt: '2026-01-02T00:00:00Z',
      },
    })

    await budgetApi.get('7')

    expect(mockedGet).toHaveBeenCalledWith('/budgets/7')
  })

  it('updates total with exactly totalAmount and expectedVersion', async () => {
    mockedPut.mockResolvedValue({ data: { id: '7' } })

    await budgetApi.update('7', { totalAmount: '150.00000000', expectedVersion: 4 })

    expect(mockedPut).toHaveBeenCalledWith('/budgets/7', {
      totalAmount: '150.00000000',
      expectedVersion: 4,
    })
  })

  it('update body never carries financial counters or status', async () => {
    mockedPut.mockResolvedValue({ data: { id: '7' } })

    await budgetApi.update('7', { totalAmount: '150.00000000', expectedVersion: 4 })

    const body = mockedPut.mock.calls[0][1] as Record<string, unknown>
    expect(Object.keys(body).sort()).toEqual(['expectedVersion', 'totalAmount'])
  })
})
