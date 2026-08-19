import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from '../../auth/AuthSessionProvider'
import type { PageResponse } from '../../../api/pagination'
import { settingsApi } from '../api/settingsApi'
import type { User } from '../api/settingsTypes'
import { UsersPage } from './UsersPage'

vi.mock('../../auth/AuthSessionProvider', () => ({ useAuth: vi.fn() }))
vi.mock('../api/settingsApi', () => ({
  settingsApi: {
    listUsers: vi.fn(),
    getUser: vi.fn(),
    updateUserStatus: vi.fn(),
    listRoles: vi.fn(),
    listPermissions: vi.fn(),
    createRoleAssignment: vi.fn(),
    revokeRoleAssignment: vi.fn(),
    createInvitation: vi.fn(),
  },
}))

const mockedUseAuth = vi.mocked(useAuth)
const mockedSettingsApi = vi.mocked(settingsApi)

const user: User = {
  id: '1',
  email: 'alpha@example.com',
  displayName: 'Alpha',
  status: 'ACTIVE',
  securityVersion: '7',
  organizationMember: { id: '11', status: 'ACTIVE', employeeNo: null, defaultCostCenterId: null },
  roleAssignments: [
    { id: '91', role: { id: 'r1', code: 'EMPLOYEE', name: 'Employee' }, scopeType: 'ORG', scopeId: '2', createdAt: '2026-08-01T00:00:00Z' },
  ],
}

function renderUsersPage(permissions: string[]) {
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
      <MemoryRouter><UsersPage /></MemoryRouter>
    </QueryClientProvider>,
  )
  return queryClient
}

const pageOf = (items: User[]): PageResponse<User> => ({ items, page: 0, size: 50, totalElements: items.length, totalPages: items.length === 0 ? 0 : 1 })

beforeEach(() => {
  vi.clearAllMocks()
  mockedSettingsApi.listUsers.mockResolvedValue(pageOf([user]))
})

describe('UsersPage', () => {
  it('usersCoversLoadingEmptyErrorAndData', async () => {
    let resolveList: (value: PageResponse<User>) => void
    mockedSettingsApi.listUsers.mockReturnValue(new Promise((resolve) => { resolveList = resolve }))
    renderUsersPage(['USER_READ'])

    expect(screen.getByText(/正在加载用户/i)).toBeInTheDocument()
    resolveList!(pageOf([user]))
    expect(await screen.findByText('Alpha')).toBeInTheDocument()
    expect(screen.getByText('alpha@example.com')).toBeInTheDocument()
    expect(screen.getByText('员工（EMPLOYEE）')).toBeInTheDocument()

    mockedSettingsApi.listUsers.mockRejectedValue({
      isAxiosError: true,
      response: { data: { title: 'Not found', status: 404, detail: 'The resource does not exist.', code: 'RESOURCE_NOT_FOUND', traceId: 't1' } },
    })
    renderUsersPage(['USER_READ'])
    expect(await screen.findByText('The resource does not exist.')).toBeInTheDocument()

    mockedSettingsApi.listUsers.mockResolvedValue(pageOf([]))
    renderUsersPage(['USER_READ'])
    expect(await screen.findByText(/该组织暂无用户/i)).toBeInTheDocument()
  })

  it('userActionsRespectIndependentPermissions', async () => {
    renderUsersPage(['USER_READ'])
    await screen.findByText('Alpha')
    expect(screen.queryByRole('button', { name: '停 用' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '邀请成员' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /分配角色/i })).not.toBeInTheDocument()
  })

  it('shows manage action only with USER_MANAGE', async () => {
    renderUsersPage(['USER_READ', 'USER_MANAGE'])
    expect(await screen.findByRole('button', { name: '停 用' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '邀请成员' })).not.toBeInTheDocument()
  })

  it('shows invite action only with USER_INVITE', async () => {
    renderUsersPage(['USER_READ', 'USER_INVITE'])
    expect(await screen.findByRole('button', { name: '邀请成员' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '停 用' })).not.toBeInTheDocument()
  })

  it('shows assign action only with ROLE_ASSIGN', async () => {
    mockedSettingsApi.listRoles.mockResolvedValue([{ id: 'r1', code: 'EMPLOYEE', name: 'Employee', permissions: [] }])
    renderUsersPage(['USER_READ', 'ROLE_ASSIGN'])
    expect(await screen.findByRole('button', { name: /分配角色/i })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '停 用' })).not.toBeInTheDocument()
  })

  it('roleAssignmentShowsFullLocalizedRoleLabelAndSubmitsRoleId', async () => {
    mockedSettingsApi.listRoles.mockResolvedValue([
      { id: 'finance-admin-id', code: 'FINANCE_ADMIN', name: 'Finance admin', permissions: [] },
    ])
    mockedSettingsApi.createRoleAssignment.mockResolvedValue({} as never)
    renderUsersPage(['USER_READ', 'ROLE_ASSIGN'])

    fireEvent.click(await screen.findByRole('button', { name: /分配角色/i }))
    fireEvent.click(await screen.findByRole('button', { name: /添加角色分配/i }))

    const dialogs = await screen.findAllByRole('dialog')
    const assignmentDialog = dialogs[dialogs.length - 1]
    const roleCombobox = within(assignmentDialog).getAllByRole('combobox')[0]
    expect(roleCombobox.closest('.ant-select')).toHaveStyle({ width: '100%' })

    fireEvent.mouseDown(roleCombobox)
    fireEvent.click(await screen.findByText('财务管理员（FINANCE_ADMIN）'))

    const scopeCombobox = within(assignmentDialog).getAllByRole('combobox')[1]
    fireEvent.mouseDown(scopeCombobox)
    fireEvent.click(await screen.findByText('ORG', { selector: '.ant-select-item-option-content' }))
    fireEvent.change(within(assignmentDialog).getByPlaceholderText('组织、项目、团队或成本中心 ID'), { target: { value: '2' } })
    fireEvent.click(within(assignmentDialog).getByRole('button', { name: /分\s*配/ }))

    await waitFor(() => {
      expect(mockedSettingsApi.createRoleAssignment).toHaveBeenCalledWith('11', 'finance-admin-id', 'ORG', '2')
    })
  })

  it('userStatusSendsExpectedVersion', async () => {
    mockedSettingsApi.updateUserStatus.mockResolvedValue({ ...user, status: 'DISABLED', securityVersion: '8' })
    renderUsersPage(['USER_READ', 'USER_MANAGE'])
    fireEvent.click(await screen.findByRole('button', { name: '停 用' }))

    await waitFor(() => {
      expect(mockedSettingsApi.updateUserStatus).toHaveBeenCalledTimes(1)
    })
    expect(mockedSettingsApi.updateUserStatus).toHaveBeenCalledWith('1', 'DISABLED', '7')
  })

  it('userStatusConflictRefetchesWithoutRetry', async () => {
    mockedSettingsApi.updateUserStatus.mockRejectedValue({
      isAxiosError: true,
      response: { data: { title: 'Conflict', status: 409, detail: 'The user was changed by another actor.', code: 'STATE_CONFLICT', traceId: 't2' } },
    })
    renderUsersPage(['USER_READ', 'USER_MANAGE'])
    fireEvent.click(await screen.findByRole('button', { name: '停 用' }))

    expect(await screen.findByText('The user was changed by another actor.')).toBeInTheDocument()
    await waitFor(() => {
      expect(mockedSettingsApi.updateUserStatus).toHaveBeenCalledTimes(1)
      expect(mockedSettingsApi.listUsers).toHaveBeenCalledTimes(2)
    })
  })

  it('invitationDoesNotOfferProjectOwnerForOrgInvite', async () => {
    mockedSettingsApi.listRoles.mockResolvedValue([
      { id: 'r1', code: 'EMPLOYEE', name: 'Employee', permissions: [] },
      { id: 'r2', code: 'PROJECT_OWNER', name: 'Project owner', permissions: [] },
      { id: 'r3', code: 'SYSTEM_ADMIN', name: 'System administrator', permissions: [] },
    ])
    renderUsersPage(['USER_READ', 'USER_INVITE'])

    fireEvent.click(await screen.findByRole('button', { name: '邀请成员' }))
    fireEvent.mouseDown(screen.getByRole('combobox'))

    // The generic ORG invitation must never offer PROJECT_OWNER.
    expect(await screen.findByText('员工（EMPLOYEE）')).toBeInTheDocument()
    expect(await screen.findByText('系统管理员（SYSTEM_ADMIN）')).toBeInTheDocument()
    expect(screen.queryByText('项目负责人（PROJECT_OWNER）')).not.toBeInTheDocument()
  })

  it('userMutationInvalidatesQueries', async () => {
    mockedSettingsApi.updateUserStatus.mockResolvedValue({ ...user, status: 'DISABLED', securityVersion: '8' })
    renderUsersPage(['USER_READ', 'USER_MANAGE'])
    fireEvent.click(await screen.findByRole('button', { name: '停 用' }))

    await waitFor(() => {
      expect(mockedSettingsApi.listUsers).toHaveBeenCalledTimes(2)
    })
    expect(mockedSettingsApi.updateUserStatus).toHaveBeenCalledTimes(1)
  })
})
