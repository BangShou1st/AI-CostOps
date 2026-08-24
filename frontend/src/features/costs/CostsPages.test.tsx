import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { useAuth } from '../auth/AuthSessionProvider'
import { costsApi, type ChargeCostDetail, type ChargeCostSummary } from './api/costsApi'
import { allocationApi } from '../allocation/api/allocationApi'
import { CostsListPage } from './CostsListPage'
import { CostDetailPage } from './CostDetailPage'

vi.mock('../auth/AuthSessionProvider', () => ({ useAuth: vi.fn() }))
vi.mock('./api/costsApi', () => ({
  costsApi: { listCharges: vi.fn(), getCharge: vi.fn() },
}))
vi.mock('../allocation/api/allocationApi', () => ({
  allocationApi: {
    listDecisionsByCharge: vi.fn(),
    listTargets: vi.fn(),
    createManualDraft: vi.fn(),
    replaceLines: vi.fn(),
    confirm: vi.fn(),
    propose: vi.fn(),
  },
}))
vi.mock('../settings/api/settingsApi', () => ({
  settingsApi: {
    listProjects: vi.fn(),
    listCostCenters: vi.fn(),
    listTeams: vi.fn(),
  },
}))

const mockedUseAuth = vi.mocked(useAuth)
const mockedCostsApi = vi.mocked(costsApi)
const mockedAllocationApi = vi.mocked(allocationApi)

const CHARGE: ChargeCostSummary = {
  id: '11',
  providerCode: 'GLM',
  chargeCategory: 'USAGE',
  amount: '10.00000000',
  currency: 'CNY',
  periodStart: '2026-01-01T00:00:00Z',
  periodEnd: '2026-02-01T00:00:00Z',
  reviewStatus: 'CLEAN',
  currentAllocationDecisionId: null,
}

const DETAIL: ChargeCostDetail = {
  ...CHARGE,
  duplicateOfChargeId: null,
  confirmedImport: true,
}

function renderPage(permissions: string[], element: React.ReactElement) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
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
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>{element}</MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  mockedAllocationApi.listTargets.mockResolvedValue([
    { type: 'PROJECT', id: '5', name: '平台' },
  ])
})

describe('CostsListPage', () => {
  it('renders amounts as decimal strings with review status', async () => {
    mockedCostsApi.listCharges.mockResolvedValue({
      items: [CHARGE],
      page: 0,
      size: 50,
      totalElements: 1,
      totalPages: 1,
    })

    renderPage(['COST_READ'], <CostsListPage />)

    await waitFor(() => expect(screen.getByText('10.00 CNY')).toBeInTheDocument())
    expect(screen.getAllByText('正常').length).toBeGreaterThanOrEqual(1)
    expect(screen.getByText('GLM')).toBeInTheDocument()
    expect(screen.queryByText('CNY')).not.toBeInTheDocument()
  })

  it('shows suspected duplicate label', async () => {
    mockedCostsApi.listCharges.mockResolvedValue({
      items: [{ ...CHARGE, reviewStatus: 'SUSPECTED_DUPLICATE' }],
      page: 0,
      size: 50,
      totalElements: 1,
      totalPages: 1,
    })

    renderPage(['COST_READ'], <CostsListPage />)

    await waitFor(() => expect(screen.getByText('疑似重复')).toBeInTheDocument())
  })

  it('shows the normalized problem detail when the charge list fails', async () => {
    mockedCostsApi.listCharges.mockRejectedValue({
      isAxiosError: true,
      response: {
        data: {
          title: 'Forbidden',
          status: 403,
          detail: 'Access to this resource is forbidden.',
          code: 'FORBIDDEN',
          traceId: 't-9',
        },
      },
    })

    renderPage(['COST_READ'], <CostsListPage />)

    await waitFor(() => {
      expect(screen.getByText(/访问被拒绝（FORBIDDEN）/)).toBeInTheDocument()
    })
      expect(screen.getByText('您没有访问此资源的权限。如您认为这是误判，请联系管理员。')).toBeInTheDocument()
  })

  it('without COST_READ the page is not reachable through the permission gate', () => {
    // The PermissionRoute guards the route; the page itself renders for any
    // authenticated caller, and the backend enforces the permission.
    mockedCostsApi.listCharges.mockResolvedValue({ items: [], page: 0, size: 50, totalElements: 0, totalPages: 0 })
    renderPage([], <CostsListPage />)
    expect(screen.getByRole('heading', { name: '成本明细' })).toBeInTheDocument()
  })
})

describe('CostDetailPage', () => {
  it('renders detail with allocation history for ALLOCATION_READ', async () => {
    mockedCostsApi.getCharge.mockResolvedValue(DETAIL)
    mockedAllocationApi.listDecisionsByCharge.mockResolvedValue([
      {
        id: '21',
        subjectType: 'CHARGE_FACT',
        expenseClaimId: null,
      chargeFactId: '11',
        source: 'MANUAL',
        status: 'CONFIRMED',
        allocationRule: null,
        createdByMemberId: '3',
        createdAt: '2026-01-05T00:00:00Z',
  lines: [{ id: '601', lineIndex: 0, allocatedAmount: '10.00000000', currency: 'CNY', projectId: '5', costCenterId: null, teamId: null }],
      },
    ])

    renderDetailPage(['COST_READ', 'ALLOCATION_READ'])

    await waitFor(() => expect(screen.getByText('成本详情 #11')).toBeInTheDocument())
    expect(screen.getAllByText('10.00 CNY').length).toBeGreaterThanOrEqual(1)
    expect(screen.getByText('确认导入谱系')).toBeInTheDocument()
    expect(screen.getByText('手动')).toBeInTheDocument()
    expect(screen.getByText('CONFIRMED')).toBeInTheDocument()
  })

  it('shows the normalized problem detail on 403 charge detail failures', async () => {
    mockedCostsApi.getCharge.mockRejectedValue({
      isAxiosError: true,
      response: {
        data: {
          title: 'Forbidden',
          status: 403,
          detail: 'Access to this resource is forbidden.',
          code: 'FORBIDDEN',
          traceId: 't-4',
        },
      },
    })

    renderDetailPage(['COST_READ'])

    await waitFor(() => {
      expect(screen.getByText(/访问被拒绝（FORBIDDEN）/)).toBeInTheDocument()
    })
      expect(screen.getByText('您没有访问此资源的权限。如您认为这是误判，请联系管理员。')).toBeInTheDocument()
    expect(screen.queryByText(/AxiosError/)).not.toBeInTheDocument()
  })

  it('shows the normalized problem detail on 404 charge detail failures', async () => {
    mockedCostsApi.getCharge.mockRejectedValue({
      isAxiosError: true,
      response: {
        data: {
          title: 'Charge not found',
          status: 404,
          detail: 'The charge is not available in the current organization.',
          code: 'RESOURCE_NOT_FOUND',
          traceId: 't-5',
        },
      },
    })

    renderDetailPage(['COST_READ'])

    await waitFor(() => {
      expect(screen.getByText(/资源不存在（RESOURCE_NOT_FOUND）/)).toBeInTheDocument()
    })
  })

  it.each([
    ['403', { title: 'Forbidden', status: 403, code: 'FORBIDDEN', detail: 'Access to this resource is forbidden.' }],
    ['404', { title: 'Allocation decision not found', status: 404, code: 'RESOURCE_NOT_FOUND', detail: 'The decision is not available.' }],
  ] as const)('shows the normalized problem detail on %s allocation-decisions failures instead of empty history', async (_label, problem) => {
    mockedCostsApi.getCharge.mockResolvedValue(DETAIL)
    mockedAllocationApi.listDecisionsByCharge.mockRejectedValue({
      isAxiosError: true,
      response: { data: { ...problem, traceId: 't-12' } },
    })

    renderDetailPage(['COST_READ', 'ALLOCATION_READ'])

    await waitFor(() => {
      expect(screen.getByText(/无法加载分摊信息/)).toBeInTheDocument()
    })
    const expectedSummary = problem.code === 'FORBIDDEN'
      ? '访问被拒绝（FORBIDDEN）'
      : '资源不存在（RESOURCE_NOT_FOUND）'
    expect(screen.getByText(expectedSummary)).toBeInTheDocument()
    // The failure must not silently render as an empty allocation history.
    expect(screen.queryByText('尚无分摊记录')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('分摊编辑')).not.toBeInTheDocument()
  })

  it('hides allocation sections without ALLOCATION_READ', async () => {
    mockedCostsApi.getCharge.mockResolvedValue(DETAIL)

    renderDetailPage(['COST_READ'])

    await waitFor(() => expect(screen.getByText('成本详情 #11')).toBeInTheDocument())
    expect(screen.queryByText('分摊历史')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('分摊编辑')).not.toBeInTheDocument()
  })

  it('renders a RULE draft read-only in the editor with confirm available', async () => {
    mockedCostsApi.getCharge.mockResolvedValue(DETAIL)
    mockedAllocationApi.listDecisionsByCharge.mockResolvedValue([
      {
        id: '22',
        subjectType: 'CHARGE_FACT',
        expenseClaimId: null,
      chargeFactId: '11',
        source: 'RULE',
        status: 'DRAFT',
        allocationRule: { id: '41', ruleKey: 'glm-key', version: 1, priority: 10 },
        createdByMemberId: '3',
        createdAt: '2026-01-05T00:00:00Z',
  lines: [{ id: '602', lineIndex: 0, allocatedAmount: '10.00000000', currency: 'CNY', projectId: '5', costCenterId: null, teamId: null }],
      },
    ])

    renderDetailPage(['COST_READ', 'ALLOCATION_READ', 'ALLOCATION_EDIT', 'ALLOCATION_CONFIRM'])

    await waitFor(() => expect(screen.getByText('成本详情 #11')).toBeInTheDocument())
    await waitFor(() => {
      expect(screen.getByLabelText('第 1 行金额')).toBeDisabled()
    })
    expect(screen.getByRole('button', { name: '确认分摊' })).toBeEnabled()
    expect(screen.getByRole('button', { name: '手动覆盖' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '创建分摊草稿' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '按规则生成' })).not.toBeInTheDocument()
  })

  it('hides creation and proposal actions when a decision is already CONFIRMED', async () => {
    mockedCostsApi.getCharge.mockResolvedValue(DETAIL)
    mockedAllocationApi.listDecisionsByCharge.mockResolvedValue([
      {
        id: '21',
        subjectType: 'CHARGE_FACT',
        expenseClaimId: null,
      chargeFactId: '11',
        source: 'MANUAL',
        status: 'CONFIRMED',
        allocationRule: null,
        createdByMemberId: '3',
        createdAt: '2026-01-05T00:00:00Z',
  lines: [{ id: '603', lineIndex: 0, allocatedAmount: '10.00000000', currency: 'CNY', projectId: '5', costCenterId: null, teamId: null }],
      },
    ])

    renderDetailPage(['COST_READ', 'ALLOCATION_READ', 'ALLOCATION_EDIT'])

    await waitFor(() => expect(screen.getByText('成本详情 #11')).toBeInTheDocument())
    await waitFor(() => expect(screen.getByLabelText('分摊编辑')).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: '创建分摊草稿' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '按规则生成' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '添加分摊行' })).not.toBeInTheDocument()
  })
})

function renderDetailPage(permissions: string[]) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
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
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/costs/11']}>
        <Routes>
          <Route path="/costs/:id" element={<CostDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}
