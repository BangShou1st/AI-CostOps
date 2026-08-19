import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { settingsApi } from '../api/settingsApi'
import { RolesPage } from './RolesPage'

vi.mock('../api/settingsApi', () => ({
  settingsApi: {
    listRoles: vi.fn(),
    listPermissions: vi.fn(),
  },
}))

const mockedSettingsApi = vi.mocked(settingsApi)

const employeeRole = {
  id: 'r1',
  code: 'EMPLOYEE',
  name: 'Employee',
  permissions: [
    { id: 'p1', code: 'USER_READ', name: 'View users' },
    { id: 'p2', code: 'PROJECT_READ', name: 'View projects' },
  ],
}

const roles = [employeeRole, { id: 'r2', code: 'SYSTEM_ADMIN', name: 'System administrator', permissions: [] }]
const permissions = [{ id: 'p1', code: 'USER_READ', name: 'View users' }, { id: 'p2', code: 'PROJECT_READ', name: 'View projects' }]

function renderRolesPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <RolesPage />
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  mockedSettingsApi.listRoles.mockResolvedValue(roles)
  mockedSettingsApi.listPermissions.mockResolvedValue(permissions)
})

describe('RolesPage', () => {
  it('rolesCatalogIsReadOnlyAndUnpaged', async () => {
    renderRolesPage()

    expect(await screen.findByText('员工（EMPLOYEE）')).toBeInTheDocument()
    expect(screen.getByText('系统管理员（SYSTEM_ADMIN）')).toBeInTheDocument()
    expect(screen.getByText('EMPLOYEE')).toBeInTheDocument()
    expect(mockedSettingsApi.listRoles).toHaveBeenCalledTimes(1)
    expect(mockedSettingsApi.listRoles).toHaveBeenCalledWith()

    // Scope applicability is presented read-only for the frozen role matrix.
    expect(screen.getByText('组织')).toBeInTheDocument()
    expect(screen.getByText(/组织、项目、团队、成本中心/)).toBeInTheDocument()

    // Permission catalog renders as read-only data.
    expect(screen.getByText('View users')).toBeInTheDocument()

    // No mutation affordance exists on the catalog.
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it('covers loading empty and error states', async () => {
    let resolveList: (value: typeof roles) => void
    mockedSettingsApi.listRoles.mockReturnValue(new Promise((resolve) => { resolveList = resolve }))
    renderRolesPage()
    expect(screen.getByText(/正在加载角色/i)).toBeInTheDocument()
    resolveList!(roles)
    expect(await screen.findByText('员工（EMPLOYEE）')).toBeInTheDocument()

    mockedSettingsApi.listRoles.mockRejectedValue({
      isAxiosError: true,
      response: { data: { title: 'Forbidden', status: 403, detail: 'You are not allowed to view roles.', code: 'FORBIDDEN', traceId: 't3' } },
    })
    renderRolesPage()
    expect(await screen.findByText('您没有访问此资源的权限。如您认为这是误判，请联系管理员。')).toBeInTheDocument()

    mockedSettingsApi.listRoles.mockResolvedValue([])
    mockedSettingsApi.listPermissions.mockResolvedValue([])
    renderRolesPage()
    expect(await screen.findByText(/尚未定义角色/i)).toBeInTheDocument()
  })
})
