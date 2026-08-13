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

    expect(await screen.findByText('Employee')).toBeInTheDocument()
    expect(screen.getByText('System administrator')).toBeInTheDocument()
    expect(screen.getByText('EMPLOYEE')).toBeInTheDocument()
    expect(mockedSettingsApi.listRoles).toHaveBeenCalledTimes(1)
    expect(mockedSettingsApi.listRoles).toHaveBeenCalledWith()

    // Scope applicability is presented read-only for the frozen role matrix.
    expect(screen.getByText('ORG')).toBeInTheDocument()
    expect(screen.getByText(/ORG, PROJECT, TEAM, COST_CENTER/)).toBeInTheDocument()

    // Permission catalog renders as read-only data.
    expect(screen.getByText('View users')).toBeInTheDocument()

    // No mutation affordance exists on the catalog.
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it('covers loading empty and error states', async () => {
    let resolveList: (value: typeof roles) => void
    mockedSettingsApi.listRoles.mockReturnValue(new Promise((resolve) => { resolveList = resolve }))
    renderRolesPage()
    expect(screen.getByText(/loading roles/i)).toBeInTheDocument()
    resolveList!(roles)
    expect(await screen.findByText('Employee')).toBeInTheDocument()

    mockedSettingsApi.listRoles.mockRejectedValue({
      isAxiosError: true,
      response: { data: { title: 'Forbidden', status: 403, detail: 'You are not allowed to view roles.', code: 'FORBIDDEN', traceId: 't3' } },
    })
    renderRolesPage()
    expect(await screen.findByText('You are not allowed to view roles.')).toBeInTheDocument()

    mockedSettingsApi.listRoles.mockResolvedValue([])
    mockedSettingsApi.listPermissions.mockResolvedValue([])
    renderRolesPage()
    expect(await screen.findByText(/no roles/i)).toBeInTheDocument()
  })
})
