import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from '../../auth/AuthSessionProvider'
import type { PageResponse } from '../../../api/pagination'
import { settingsApi } from '../api/settingsApi'
import type { MasterDataRecord } from '../api/settingsTypes'
import { CostCentersPage } from './CostCentersPage'

vi.mock('../../auth/AuthSessionProvider', () => ({ useAuth: vi.fn() }))
vi.mock('../api/settingsApi', () => ({
  settingsApi: {
    listCostCenters: vi.fn(),
    createCostCenter: vi.fn(),
    updateCostCenter: vi.fn(),
  },
}))

const mockedUseAuth = vi.mocked(useAuth)
const mockedSettingsApi = vi.mocked(settingsApi)

const costCenter: MasterDataRecord = {
  id: '5', code: 'PLATFORM', name: 'Platform Engineering', status: 'ARCHIVED',
  createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-02T00:00:00Z',
}

function renderCostCentersPage(permissions: string[]) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  mockedUseAuth.mockReturnValue({
    status: 'authenticated',
    user: { id: '1', email: 'admin@example.com', displayName: 'Admin', organizationId: '2', organizationMemberId: '11', permissions },
    login: vi.fn(),
    refreshMe: vi.fn(),
    logout: vi.fn(),
  } as ReturnType<typeof useAuth>)
  render(
    <QueryClientProvider client={queryClient}>
      <CostCentersPage />
    </QueryClientProvider>,
  )
}

const pageOf = (items: MasterDataRecord[]): PageResponse<MasterDataRecord> => ({ items, page: 0, size: 50, totalElements: items.length, totalPages: items.length === 0 ? 0 : 1 })

beforeEach(() => {
  vi.clearAllMocks()
  mockedSettingsApi.listCostCenters.mockResolvedValue(pageOf([costCenter]))
})

describe('CostCentersPage', () => {
  it('costCentersCoverAllQueryStates', async () => {
    let resolveList: (value: PageResponse<MasterDataRecord>) => void
    mockedSettingsApi.listCostCenters.mockReturnValue(new Promise((resolve) => { resolveList = resolve }))
    renderCostCentersPage(['COST_CENTER_READ', 'COST_CENTER_MANAGE'])

    expect(screen.getByText(/正在加载成本中心/i)).toBeInTheDocument()
    resolveList!(pageOf([costCenter]))
    expect(await screen.findByText('PLATFORM')).toBeInTheDocument()
    expect(screen.getByText('Platform Engineering')).toBeInTheDocument()
    expect(screen.getByText('已归档')).toBeInTheDocument()

    // Edit keeps the code immutable and preserves the current status.
    fireEvent.click(screen.getByRole('button', { name: '编 辑' }))
    expect(await screen.findByLabelText(/编码/)).toHaveValue('PLATFORM')
    expect(screen.getByLabelText(/编码/)).toBeDisabled()
    fireEvent.change(screen.getByLabelText(/名称/), { target: { value: 'Platform Eng' } })
    fireEvent.click(screen.getByRole('button', { name: /保\s*存/ }))
    await waitFor(() => {
      expect(mockedSettingsApi.updateCostCenter).toHaveBeenCalledWith('5', { name: 'Platform Eng', status: 'ARCHIVED' })
    })

    mockedSettingsApi.listCostCenters.mockRejectedValue({
      isAxiosError: true,
      response: { data: { title: 'Forbidden', status: 403, detail: 'You cannot view cost centers.', code: 'FORBIDDEN', traceId: 't6' } },
    })
    renderCostCentersPage(['COST_CENTER_READ'])
    expect(await screen.findByText('您没有访问此资源的权限。如您认为这是误判，请联系管理员。')).toBeInTheDocument()

    mockedSettingsApi.listCostCenters.mockResolvedValue(pageOf([]))
    renderCostCentersPage(['COST_CENTER_READ'])
    expect(await screen.findByText(/该组织暂无成本中心/i)).toBeInTheDocument()
  }, 15_000)

  it('costCenterActionsRequireManage', async () => {
    renderCostCentersPage(['COST_CENTER_READ'])
    await screen.findByText('PLATFORM')

    expect(screen.queryByRole('button', { name: '创建成本中心' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '编 辑' })).not.toBeInTheDocument()
  })

  it('costCenterMutationInvalidatesQueries', async () => {
    mockedSettingsApi.createCostCenter.mockResolvedValue({ ...costCenter, id: '9' })
    renderCostCentersPage(['COST_CENTER_READ', 'COST_CENTER_MANAGE'])

    fireEvent.click(await screen.findByRole('button', { name: '创建成本中心' }))
    fireEvent.change(await screen.findByLabelText(/编码/), { target: { value: 'MKT' } })
    fireEvent.change(screen.getByLabelText(/名称/), { target: { value: 'Marketing' } })
    fireEvent.click(screen.getByRole('button', { name: /创\s*建$/ }))

    await waitFor(() => {
      expect(mockedSettingsApi.createCostCenter).toHaveBeenCalledTimes(1)
      expect(mockedSettingsApi.createCostCenter).toHaveBeenCalledWith('MKT', 'Marketing')
      expect(mockedSettingsApi.listCostCenters).toHaveBeenCalledTimes(2)
    })
  })
})
