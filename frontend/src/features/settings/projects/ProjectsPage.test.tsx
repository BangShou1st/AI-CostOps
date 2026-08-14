import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from '../../auth/AuthSessionProvider'
import type { PageResponse } from '../../../api/pagination'
import { settingsApi } from '../api/settingsApi'
import type { MasterDataRecord, OrganizationMemberRecord, User } from '../api/settingsTypes'
import { ProjectsPage } from './ProjectsPage'

vi.mock('../../auth/AuthSessionProvider', () => ({ useAuth: vi.fn() }))
vi.mock('../api/settingsApi', () => ({
  settingsApi: {
    listProjects: vi.fn(),
    createProject: vi.fn(),
    updateProject: vi.fn(),
    listProjectMembers: vi.fn(),
    addProjectMember: vi.fn(),
    removeProjectMember: vi.fn(),
    listUsers: vi.fn(),
  },
}))

const mockedUseAuth = vi.mocked(useAuth)
const mockedSettingsApi = vi.mocked(settingsApi)

const project: MasterDataRecord = {
  id: '1', code: 'CORE', name: 'Core Platform', status: 'ACTIVE',
  createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-02T00:00:00Z',
}

const member: OrganizationMemberRecord = {
  id: 'm1', organizationMemberId: '11', userId: '1', email: 'alpha@example.com',
  displayName: 'Alpha', userStatus: 'ACTIVE', status: 'ACTIVE', joinedAt: '2026-08-03T00:00:00Z',
}

const user: User = {
  id: '1', email: 'alpha@example.com', displayName: 'Alpha', status: 'ACTIVE', securityVersion: '7',
  organizationMember: { id: '11', status: 'ACTIVE', employeeNo: null, defaultCostCenterId: null },
  roleAssignments: [],
}

function renderProjectsPage(permissions: string[]) {
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
      <MemoryRouter><ProjectsPage /></MemoryRouter>
    </QueryClientProvider>,
  )
}

const pageOf = <T,>(items: T[]): PageResponse<T> => ({ items, page: 0, size: 50, totalElements: items.length, totalPages: items.length === 0 ? 0 : 1 })

beforeEach(() => {
  vi.clearAllMocks()
  mockedSettingsApi.listProjects.mockResolvedValue(pageOf([project]))
  mockedSettingsApi.listProjectMembers.mockResolvedValue(pageOf([member]))
  mockedSettingsApi.listUsers.mockResolvedValue(pageOf([user]))
})

describe('ProjectsPage', () => {
  it('projectsCoverLifecycleAndMembershipStates', async () => {
    let resolveList: (value: PageResponse<MasterDataRecord>) => void
    mockedSettingsApi.listProjects.mockReturnValue(new Promise((resolve) => { resolveList = resolve }))
    renderProjectsPage(['PROJECT_READ', 'PROJECT_MANAGE', 'PROJECT_MEMBER_MANAGE'])

    expect(screen.getByText(/正在加载项目/i)).toBeInTheDocument()
    resolveList!(pageOf([project]))
    expect(await screen.findByText('CORE')).toBeInTheDocument()
    expect(screen.getByText('Core Platform')).toBeInTheDocument()
    expect(screen.getByText('启用')).toBeInTheDocument()

    // Membership drawer lists project members.
    fireEvent.click(screen.getByRole('button', { name: /成\s*员/ }))
    expect(await screen.findByText('Alpha')).toBeInTheDocument()
    expect(mockedSettingsApi.listProjectMembers).toHaveBeenCalledTimes(1)
    expect(mockedSettingsApi.listProjectMembers).toHaveBeenCalledWith('1', 0, 50)

    mockedSettingsApi.listProjects.mockRejectedValue({
      isAxiosError: true,
      response: { data: { title: 'Forbidden', status: 403, detail: 'You cannot view projects.', code: 'FORBIDDEN', traceId: 't4' } },
    })
    renderProjectsPage(['PROJECT_READ'])
    expect(await screen.findByText('You cannot view projects.')).toBeInTheDocument()

    mockedSettingsApi.listProjects.mockResolvedValue(pageOf([]))
    renderProjectsPage(['PROJECT_READ'])
    expect(await screen.findByText(/该组织暂无项目/i)).toBeInTheDocument()
  })

  it('projectActionsRequireManage', async () => {
    renderProjectsPage(['PROJECT_READ'])
    await screen.findByText('CORE')

    expect(screen.queryByRole('button', { name: '创建项目' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /编\s*辑/ })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /成\s*员/ }))
    await screen.findByText('Alpha')
    expect(screen.queryByRole('button', { name: '添加成员' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /移\s*除/ })).not.toBeInTheDocument()
  })

  it('projectMemberManageWorksWithoutUserRead', async () => {
    renderProjectsPage(['PROJECT_READ', 'PROJECT_MEMBER_MANAGE'])

    fireEvent.click(await screen.findByRole('button', { name: /成\s*员/ }))
    await screen.findByText('Alpha')

    // Without USER_READ the /users candidate query must never fire.
    expect(mockedSettingsApi.listUsers).not.toHaveBeenCalled()
    const input = screen.getByLabelText(/组织成员 ID/)
    fireEvent.change(input, { target: { value: '42' } })
    fireEvent.click(screen.getByRole('button', { name: '添加成员' }))

    await waitFor(() => {
      expect(mockedSettingsApi.addProjectMember).toHaveBeenCalledTimes(1)
      expect(mockedSettingsApi.addProjectMember).toHaveBeenCalledWith('1', '42')
    })
  })

  it('projectMemberTargetFollowsCurrentPermissionMode', async () => {
    const permissionsRef = { current: ['PROJECT_READ', 'PROJECT_MEMBER_MANAGE', 'USER_READ'] }
    mockedUseAuth.mockImplementation(() => ({
      status: 'authenticated',
      user: { id: '1', email: 'admin@example.com', displayName: 'Admin', organizationId: '2', organizationMemberId: '11', permissions: permissionsRef.current },
      login: vi.fn(),
      refreshMe: vi.fn(),
      logout: vi.fn(),
    } as ReturnType<typeof useAuth>))
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const { rerender } = render(
      <QueryClientProvider client={queryClient}><MemoryRouter><ProjectsPage /></MemoryRouter></QueryClientProvider>,
    )

    // Select mode with USER_READ: pick member A.
    fireEvent.click(await screen.findByRole('button', { name: /成\s*员/ }))
    await screen.findByText('Alpha')
    fireEvent.mouseDown(screen.getByRole('combobox'))
    fireEvent.click(await screen.findByText('Alpha (alpha@example.com)'))

    // Permission refresh revokes USER_READ but keeps PROJECT_MEMBER_MANAGE.
    permissionsRef.current = ['PROJECT_READ', 'PROJECT_MEMBER_MANAGE']
    rerender(
      <QueryClientProvider client={queryClient}><MemoryRouter><ProjectsPage /></MemoryRouter></QueryClientProvider>,
    )

    // Manual mode: member B is typed and submitted; the stale Select target A
    // must never win.
    const usersCalls = mockedSettingsApi.listUsers.mock.calls.length
    fireEvent.change(await screen.findByLabelText(/组织成员 ID/), { target: { value: '42' } })
    fireEvent.click(screen.getByRole('button', { name: '添加成员' }))

    await waitFor(() => {
      expect(mockedSettingsApi.addProjectMember).toHaveBeenCalledTimes(1)
      expect(mockedSettingsApi.addProjectMember).toHaveBeenCalledWith('1', '42')
    })
    expect(mockedSettingsApi.listUsers.mock.calls.length).toBe(usersCalls)
  })

  it('organizationMutationsInvalidateExactKeys', async () => {
    mockedSettingsApi.createProject.mockResolvedValue({ ...project, id: '9' })
    renderProjectsPage(['PROJECT_READ', 'PROJECT_MANAGE', 'PROJECT_MEMBER_MANAGE', 'USER_READ'])

    fireEvent.click(await screen.findByRole('button', { name: '创建项目' }))
    const codeInput = await screen.findByLabelText(/编码/)
    fireEvent.change(codeInput, { target: { value: 'PAY' } })
    fireEvent.change(screen.getByLabelText(/名称/), { target: { value: 'Payments' } })
    fireEvent.click(screen.getByRole('button', { name: /创\s*建$/ }))

    await waitFor(() => {
      expect(mockedSettingsApi.createProject).toHaveBeenCalledTimes(1)
      expect(mockedSettingsApi.createProject).toHaveBeenCalledWith('PAY', 'Payments')
      expect(mockedSettingsApi.listProjects).toHaveBeenCalledTimes(2)
    })

    mockedSettingsApi.addProjectMember.mockResolvedValue(member)
    fireEvent.click(screen.getByRole('button', { name: /成\s*员/ }))
    await screen.findByText('Alpha')
    fireEvent.mouseDown(screen.getByRole('combobox'))
    fireEvent.click(await screen.findByText('Alpha (alpha@example.com)'))
    fireEvent.click(screen.getByRole('button', { name: '添加成员' }))

    await waitFor(() => {
      expect(mockedSettingsApi.addProjectMember).toHaveBeenCalledTimes(1)
      expect(mockedSettingsApi.addProjectMember).toHaveBeenCalledWith('1', '11')
      expect(mockedSettingsApi.listProjectMembers).toHaveBeenCalledTimes(2)
    })
  })
})
