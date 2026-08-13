import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from '../../auth/AuthSessionProvider'
import type { PageResponse } from '../../../api/pagination'
import { settingsApi } from '../api/settingsApi'
import type { MasterDataRecord, OrganizationMemberRecord, User } from '../api/settingsTypes'
import { TeamsPage } from './TeamsPage'

vi.mock('../../auth/AuthSessionProvider', () => ({ useAuth: vi.fn() }))
vi.mock('../api/settingsApi', () => ({
  settingsApi: {
    listTeams: vi.fn(),
    createTeam: vi.fn(),
    updateTeam: vi.fn(),
    listTeamMembers: vi.fn(),
    addTeamMember: vi.fn(),
    removeTeamMember: vi.fn(),
    listUsers: vi.fn(),
  },
}))

const mockedUseAuth = vi.mocked(useAuth)
const mockedSettingsApi = vi.mocked(settingsApi)

const team: MasterDataRecord = {
  id: '2', code: 'OPS', name: 'Operations', status: 'DISABLED',
  createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-02T00:00:00Z',
}

const member: OrganizationMemberRecord = {
  id: 'm2', organizationMemberId: '12', userId: '2', email: 'beta@example.com',
  displayName: 'Beta', userStatus: 'ACTIVE', status: 'ACTIVE', joinedAt: '2026-08-03T00:00:00Z',
}

const user: User = {
  id: '2', email: 'beta@example.com', displayName: 'Beta', status: 'ACTIVE', securityVersion: '4',
  organizationMember: { id: '12', status: 'ACTIVE', employeeNo: null, defaultCostCenterId: null },
  roleAssignments: [],
}

function renderTeamsPage(permissions: string[]) {
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
      <MemoryRouter><TeamsPage /></MemoryRouter>
    </QueryClientProvider>,
  )
}

const pageOf = <T,>(items: T[]): PageResponse<T> => ({ items, page: 0, size: 50, totalElements: items.length, totalPages: items.length === 0 ? 0 : 1 })

beforeEach(() => {
  vi.clearAllMocks()
  mockedSettingsApi.listTeams.mockResolvedValue(pageOf([team]))
  mockedSettingsApi.listTeamMembers.mockResolvedValue(pageOf([member]))
  mockedSettingsApi.listUsers.mockResolvedValue(pageOf([user]))
})

describe('TeamsPage', () => {
  it('teamsCoverLifecycleAndMembershipStates', async () => {
    let resolveList: (value: PageResponse<MasterDataRecord>) => void
    mockedSettingsApi.listTeams.mockReturnValue(new Promise((resolve) => { resolveList = resolve }))
    renderTeamsPage(['TEAM_READ', 'TEAM_MANAGE'])

    expect(screen.getByText(/loading teams/i)).toBeInTheDocument()
    resolveList!(pageOf([team]))
    expect(await screen.findByText('OPS')).toBeInTheDocument()
    expect(screen.getByText('Operations')).toBeInTheDocument()
    expect(screen.getByText('disabled')).toBeInTheDocument()

    // Lifecycle edit keeps the code immutable.
    fireEvent.click(screen.getByRole('button', { name: 'Edit' }))
    expect(await screen.findByLabelText(/code/i)).toHaveValue('OPS')
    expect(screen.getByLabelText(/code/i)).toBeDisabled()

    fireEvent.change(screen.getByLabelText(/name/i), { target: { value: 'Operations II' } })
    fireEvent.click(screen.getByRole('button', { name: /save/i }))
    await waitFor(() => {
      expect(mockedSettingsApi.updateTeam).toHaveBeenCalledWith('2', { name: 'Operations II', status: 'DISABLED' })
    })

    // Membership drawer lists team members.
    fireEvent.click(screen.getByRole('button', { name: /members/i }))
    expect(await screen.findByText('Beta')).toBeInTheDocument()
    expect(mockedSettingsApi.listTeamMembers).toHaveBeenCalledWith('2', 0, 50)

    mockedSettingsApi.listTeams.mockRejectedValue({
      isAxiosError: true,
      response: { data: { title: 'Forbidden', status: 403, detail: 'You cannot view teams.', code: 'FORBIDDEN', traceId: 't5' } },
    })
    renderTeamsPage(['TEAM_READ'])
    expect(await screen.findByText('You cannot view teams.')).toBeInTheDocument()

    mockedSettingsApi.listTeams.mockResolvedValue(pageOf([]))
    renderTeamsPage(['TEAM_READ'])
    expect(await screen.findByText(/no teams/i)).toBeInTheDocument()
  })

  it('teamActionsRequireManage', async () => {
    renderTeamsPage(['TEAM_READ'])
    await screen.findByText('OPS')

    expect(screen.queryByRole('button', { name: 'Create team' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /members/i }))
    await screen.findByText('Beta')
    expect(screen.queryByRole('button', { name: /add member/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Remove' })).not.toBeInTheDocument()
  })

  it('teamMemberManageWorksWithoutUserRead', async () => {
    renderTeamsPage(['TEAM_READ', 'TEAM_MANAGE'])

    fireEvent.click(await screen.findByRole('button', { name: /members/i }))
    await screen.findByText('Beta')

    // Without USER_READ the /users candidate query must never fire.
    expect(mockedSettingsApi.listUsers).not.toHaveBeenCalled()
    const input = screen.getByLabelText(/organization member id/i)
    fireEvent.change(input, { target: { value: '99' } })
    fireEvent.click(screen.getByRole('button', { name: /add member/i }))

    await waitFor(() => {
      expect(mockedSettingsApi.addTeamMember).toHaveBeenCalledTimes(1)
      expect(mockedSettingsApi.addTeamMember).toHaveBeenCalledWith('2', '99')
    })
  })

  it('teamMemberTargetFollowsCurrentPermissionMode', async () => {
    const permissionsRef = { current: ['TEAM_READ', 'TEAM_MANAGE', 'USER_READ'] }
    mockedUseAuth.mockImplementation(() => ({
      status: 'authenticated',
      user: { id: '1', email: 'admin@example.com', displayName: 'Admin', organizationId: '2', organizationMemberId: '11', permissions: permissionsRef.current },
      login: vi.fn(),
      refreshMe: vi.fn(),
      logout: vi.fn(),
    } as ReturnType<typeof useAuth>))
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const { rerender } = render(
      <QueryClientProvider client={queryClient}><MemoryRouter><TeamsPage /></MemoryRouter></QueryClientProvider>,
    )

    // Select mode with USER_READ: pick member A.
    fireEvent.click(await screen.findByRole('button', { name: /members/i }))
    await screen.findByText('Beta')
    fireEvent.mouseDown(screen.getByRole('combobox'))
    fireEvent.click(await screen.findByText('Beta (beta@example.com)'))

    // Permission refresh revokes USER_READ but keeps TEAM_MANAGE.
    permissionsRef.current = ['TEAM_READ', 'TEAM_MANAGE']
    rerender(
      <QueryClientProvider client={queryClient}><MemoryRouter><TeamsPage /></MemoryRouter></QueryClientProvider>,
    )

    // Manual mode: member B is typed and submitted; the stale Select target A
    // must never win.
    const usersCalls = mockedSettingsApi.listUsers.mock.calls.length
    fireEvent.change(await screen.findByLabelText(/organization member id/i), { target: { value: '77' } })
    fireEvent.click(screen.getByRole('button', { name: /add member/i }))

    await waitFor(() => {
      expect(mockedSettingsApi.addTeamMember).toHaveBeenCalledTimes(1)
      expect(mockedSettingsApi.addTeamMember).toHaveBeenCalledWith('2', '77')
    })
    expect(mockedSettingsApi.listUsers.mock.calls.length).toBe(usersCalls)
  })

  it('teamMembershipMutationInvalidatesMembers', async () => {
    mockedSettingsApi.removeTeamMember.mockResolvedValue(undefined)
    renderTeamsPage(['TEAM_READ', 'TEAM_MANAGE'])
    fireEvent.click(await screen.findByRole('button', { name: /members/i }))
    await screen.findByText('Beta')

    fireEvent.click(screen.getByRole('button', { name: 'Remove' }))
    await waitFor(() => {
      expect(mockedSettingsApi.removeTeamMember).toHaveBeenCalledTimes(1)
      expect(mockedSettingsApi.removeTeamMember).toHaveBeenCalledWith('2', 'm2')
      expect(mockedSettingsApi.listTeamMembers).toHaveBeenCalledTimes(2)
    })
  })
})
