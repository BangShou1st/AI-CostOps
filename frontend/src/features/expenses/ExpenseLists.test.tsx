import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ExpensesListPage } from './ExpensesListPage'
import { ExpenseReviewQueuePage } from './ExpenseReviewQueuePage'
import { expenseApi } from './api/expenseApi'

vi.mock('../auth/AuthSessionProvider', () => ({
  useAuth: () => ({ user: { permissions: ['EXPENSE_READ_OWN'] } }),
}))
vi.mock('./api/expenseApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./api/expenseApi')>()
  return { ...actual, expenseApi: { ...actual.expenseApi, listMine: vi.fn(), listReviewQueue: vi.fn() } }
})

afterEach(() => vi.resetAllMocks())

describe.each([
  { name: '我的报销', Page: ExpensesListPage, api: vi.mocked(expenseApi.listMine), error: '报销列表加载失败' },
  { name: '费用审核', Page: ExpenseReviewQueuePage, api: vi.mocked(expenseApi.listReviewQueue), error: '审核列表加载失败' },
])('$name list failures', ({ Page, api, error }) => {
  it('shows failure instead of an empty table and can retry successfully', async () => {
    api.mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(<QueryClientProvider client={client}><MemoryRouter><Page /></MemoryRouter></QueryClientProvider>)
    expect(await screen.findByText(error)).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(api).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }))
    expect(await screen.findByRole('table')).toBeInTheDocument()
    expect(screen.queryByText(error)).not.toBeInTheDocument()
    expect(api).toHaveBeenCalledTimes(2)
    client.clear()
  })
})
