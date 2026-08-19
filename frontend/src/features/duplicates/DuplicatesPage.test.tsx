import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import { useAuth } from '../auth/AuthSessionProvider'
import { duplicatesApi, type DuplicateCandidate } from './api/duplicatesApi'
import { DuplicatesPage } from './DuplicatesPage'

vi.mock('../auth/AuthSessionProvider', () => ({ useAuth: vi.fn() }))
vi.mock('./api/duplicatesApi', () => ({
  duplicatesApi: { listCandidates: vi.fn(), keep: vi.fn(), exclude: vi.fn() },
}))

const mockedUseAuth = vi.mocked(useAuth)
const mockedDuplicatesApi = vi.mocked(duplicatesApi)

const CANDIDATE: DuplicateCandidate = {
  id: '31',
  candidateType: 'EXACT',
  fingerprint: 'f1',
  algorithmVersion: 'v1',
  matchReason: '金额与周期完全一致',
  status: 'OPEN',
  chargeFact: {
    id: '11',
    providerCode: 'GLM',
    chargeCategory: 'USAGE',
    amount: '10.00000000',
    currency: 'CNY',
    periodStart: '2026-01-01T00:00:00Z',
    periodEnd: '2026-02-01T00:00:00Z',
    reviewStatus: 'SUSPECTED_DUPLICATE',
    duplicateOfChargeId: null,
  },
  matchedChargeFact: {
    id: '12',
    providerCode: 'GLM',
    chargeCategory: 'USAGE',
    amount: '10.00000000',
    currency: 'CNY',
    periodStart: '2026-01-01T00:00:00Z',
    periodEnd: '2026-02-01T00:00:00Z',
    reviewStatus: 'SUSPECTED_DUPLICATE',
    duplicateOfChargeId: null,
  },
  createdAt: '2026-01-02T00:00:00Z',
  resolvedAt: null,
}

function renderPage(permissions: string[]) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')
  mockedUseAuth.mockReturnValue({
    status: 'authenticated',
    user: {
      id: '1',
      email: 'admin@example.com',
      displayName: 'Admin',
      organizationId: '2',
      organizationMemberId: '3',
      permissions,
    },
    login: vi.fn(),
    refreshMe: vi.fn(),
    logout: vi.fn(),
  } as ReturnType<typeof useAuth>)
  const rendered = render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter><DuplicatesPage /></MemoryRouter>
    </QueryClientProvider>,
  )
  return { ...rendered, invalidateSpy }
}

beforeEach(() => {
  vi.clearAllMocks()
  mockedDuplicatesApi.listCandidates.mockResolvedValue({
    items: [CANDIDATE],
    page: 0,
    size: 50,
    totalElements: 1,
    totalPages: 1,
  })
})

describe('DuplicatesPage', () => {
  it('renders OPEN candidates with evidence summary and decimal money', async () => {
    renderPage(['COST_READ', 'DUPLICATE_REVIEW'])

    await waitFor(() => expect(screen.getByText('金额与周期完全一致')).toBeInTheDocument())
    expect(screen.getAllByText(/10\.00000000/).length).toBeGreaterThanOrEqual(2)
    expect(screen.getByRole('button', { name: '保留正常' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '排除源方 #11' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '排除匹配方 #12' })).toBeInTheDocument()
  })

  it('keep clean calls the real contract and refreshes the list', async () => {
    mockedDuplicatesApi.keep.mockResolvedValue({ ...CANDIDATE, status: 'KEPT_CLEAN' })
    renderPage(['COST_READ', 'DUPLICATE_REVIEW'])

    await waitFor(() => expect(screen.getByRole('button', { name: '保留正常' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: '保留正常' }))

    await waitFor(() => expect(mockedDuplicatesApi.keep).toHaveBeenCalledTimes(1))
    expect(mockedDuplicatesApi.keep.mock.calls[0][0]).toBe('31')
    // mutation refresh triggers a second list fetch
    await waitFor(() => expect(mockedDuplicatesApi.listCandidates).toHaveBeenCalledTimes(2))
  })

  it('excluding the source side sends the source charge id', async () => {
    mockedDuplicatesApi.exclude.mockResolvedValue({ ...CANDIDATE, status: 'CONFIRMED_DUPLICATE' })
    renderPage(['COST_READ', 'DUPLICATE_REVIEW'])

    await waitFor(() => expect(screen.getByRole('button', { name: '排除源方 #11' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: '排除源方 #11' }))

    await waitFor(() => expect(mockedDuplicatesApi.exclude).toHaveBeenCalledTimes(1))
    const [candidateId, excludedChargeFactId] = mockedDuplicatesApi.exclude.mock.calls[0]
    expect(candidateId).toBe('31')
    expect(excludedChargeFactId).toBe('11')
  })

  it('excluding the matched side sends the matched charge id', async () => {
    mockedDuplicatesApi.exclude.mockResolvedValue({ ...CANDIDATE, status: 'CONFIRMED_DUPLICATE' })
    renderPage(['COST_READ', 'DUPLICATE_REVIEW'])

    await waitFor(() => expect(screen.getByRole('button', { name: '排除匹配方 #12' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: '排除匹配方 #12' }))

    await waitFor(() => expect(mockedDuplicatesApi.exclude).toHaveBeenCalledTimes(1))
    const [candidateId, excludedChargeFactId] = mockedDuplicatesApi.exclude.mock.calls[0]
    expect(candidateId).toBe('31')
    expect(excludedChargeFactId).toBe('12')
  })

  it('invalidates both charges detail and allocation queries plus the candidate detail on resolve', async () => {
    mockedDuplicatesApi.keep.mockResolvedValue({ ...CANDIDATE, status: 'KEPT_CLEAN' })
    const { invalidateSpy } = renderPage(['COST_READ', 'DUPLICATE_REVIEW'])

    await waitFor(() => expect(screen.getByRole('button', { name: '保留正常' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: '保留正常' }))

    await waitFor(() => expect(mockedDuplicatesApi.keep).toHaveBeenCalledTimes(1))
    await waitFor(() => {
      const invalidatedKeys = invalidateSpy.mock.calls.map((call) => JSON.stringify(call[0]?.queryKey))
      expect(invalidatedKeys).toContain(JSON.stringify(['duplicates', 'list']))
      expect(invalidatedKeys).toContain(JSON.stringify(['duplicates', 'detail', '31']))
      expect(invalidatedKeys).toContain(JSON.stringify(['costs', 'list']))
      expect(invalidatedKeys).toContain(JSON.stringify(['costs', 'detail', '11']))
      expect(invalidatedKeys).toContain(JSON.stringify(['costs', 'detail', '12']))
      expect(invalidatedKeys).toContain(JSON.stringify(['allocation', 'charge', '11']))
      expect(invalidatedKeys).toContain(JSON.stringify(['allocation', 'charge', '12']))
    })
  })

  it.each([
    ['403', { title: 'Forbidden', status: 403, code: 'FORBIDDEN', detail: 'Access to this resource is forbidden.' }],
    ['404', { title: 'Duplicate candidate not found', status: 404, code: 'RESOURCE_NOT_FOUND', detail: 'The candidate is not available.' }],
  ] as const)('shows the normalized problem detail on %s list failures instead of an empty list', async (_label, problem) => {
    mockedDuplicatesApi.listCandidates.mockRejectedValue({
      isAxiosError: true,
      response: { data: { ...problem, traceId: 't-11' } },
    })
    renderPage(['COST_READ', 'DUPLICATE_REVIEW'])

    const expectedSummary = problem.code === 'FORBIDDEN'
      ? '访问被拒绝（FORBIDDEN）'
      : `${problem.title}（${problem.code}）`
    const expectedDetail = problem.code === 'FORBIDDEN'
      ? '您没有访问此资源的权限。如您认为这是误判，请联系管理员。'
      : problem.detail
    await waitFor(() => {
      expect(screen.getByText(expectedSummary)).toBeInTheDocument()
    })
    expect(screen.getByText(expectedDetail)).toBeInTheDocument()
    // The failure must not silently render as "no pending candidates".
    expect(screen.queryByText('没有待处理的疑似重复候选')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '保留正常' })).not.toBeInTheDocument()
  })

  it('displays the backend problem code on failure', async () => {
    mockedDuplicatesApi.keep.mockRejectedValue({
      isAxiosError: true,
      response: {
        data: {
          title: 'Candidate is not open',
          status: 409,
          detail: 'The candidate was already resolved.',
          code: 'STATE_CONFLICT',
          traceId: 't-2',
        },
      },
    })
    renderPage(['COST_READ', 'DUPLICATE_REVIEW'])

    await waitFor(() => expect(screen.getByRole('button', { name: '保留正常' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: '保留正常' }))

    await waitFor(() => {
      expect(screen.getByText(/Candidate is not open（STATE_CONFLICT）/)).toBeInTheDocument()
    })
    expect(screen.getByText('The candidate was already resolved.')).toBeInTheDocument()
  })

  it('without DUPLICATE_REVIEW shows the permission warning', () => {
    renderPage(['COST_READ'])

    expect(screen.getByText('缺少 DUPLICATE_REVIEW 权限')).toBeInTheDocument()
  })
})
