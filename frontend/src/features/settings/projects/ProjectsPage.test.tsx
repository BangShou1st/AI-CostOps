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

    expect(screen.getByText(/loading projects/i)).toBeInTheDocument()
    resolveList!(pageOf([project]))
    expect(await screen.findByText('CORE')).toBeInTheDocument()
    expect(screen.getByText('Core Platform')).toBeInTheDocument()
    expect(screen.getByText('active')).toBeInTheDocument()

    // Membership drawer lists project members.
    fireEvent.click(screen.getByRole('button', { name: /members/i }))
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
    expect(await screen.findByText(/no projects/i)).toBeInTheDocument()
  })

  it('projectActionsRequireManage', async () => {
    renderProjectsPage(['PROJECT_READ'])
    await screen.findByText('CORE')

    expect(screen.queryByRole('button', { name: 'Create project' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /members/i }))
    await screen.findByText('Alpha')
    expect(screen.queryByRole('button', { name: /add member/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Remove' })).not.toBeInTheDocument()
  })

  it('organizationMutationsInvalidateExactKeys', async () => {
    mockedSettingsApi.createProject.mockResolvedValue({ ...project, id: '9' })
    renderProjectsPage(['PROJECT_READ', 'PROJECT_MANAGE', 'PROJECT_MEMBER_MANAGE'])

    fireEvent.click(await screen.findByRole('button', { name: 'Create project' }))
    const codeInput = await screen.findByLabelText(/code/i)
    fireEvent.change(codeInput, { target: { value: 'PAY' } })
    fireEvent.change(screen.getByLabelText(/name/i), { target: { value: 'Payments' } })
    fireEvent.click(screen.getByRole('button', { name: /create$/i }))

    await waitFor(() => {
      expect(mockedSettingsApi.createProject).toHaveBeenCalledTimes(1)
      expect(mockedSettingsApi.createProject).toHaveBeenCalledWith('PAY', 'Payments')
      expect(mockedSettingsApi.listProjects).toHaveBeenCalledTimes(2)
    })

    mockedSettingsApi.addProjectMember.mockResolvedValue(member)
    fireEvent.click(screen.getByRole('button', { name: /members/i }))
    await screen.findByText('Alpha')
    fireEvent.mouseDown(screen.getByRole('combobox'))
    fireEvent.click(await screen.findByText('Alpha (alpha@example.com)'))
    fireEvent.click(screen.getByRole('button', { name: /add member/i }))

    await waitFor(() => {
      expect(mockedSettingsApi.addProjectMember).toHaveBeenCalledTimes(1)
      expect(mockedSettingsApi.addProjectMember).toHaveBeenCalledWith('1', '11')
      expect(mockedSettingsApi.listProjectMembers).toHaveBeenCalledTimes(2)
    })
  })
})
