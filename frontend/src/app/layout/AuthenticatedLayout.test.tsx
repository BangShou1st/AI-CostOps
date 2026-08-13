import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from '../../features/auth/AuthSessionProvider'
import { AuthenticatedLayout } from './AuthenticatedLayout'

vi.mock('../../features/auth/AuthSessionProvider', () => ({ useAuth: vi.fn() }))

const mockedUseAuth = vi.mocked(useAuth)

function renderLayout(permissions: string[]) {
  mockedUseAuth.mockReturnValue({
    status: 'authenticated',
    user: { id: '1', email: 'admin@example.com', displayName: 'Admin', organizationId: '2', organizationMemberId: '3', permissions },
    login: vi.fn(),
    refreshMe: vi.fn(),
    logout: vi.fn(),
  } as ReturnType<typeof useAuth>)
  render(
    <MemoryRouter initialEntries={['/settings/users']}>
      <Routes>
        <Route element={<AuthenticatedLayout />}>
          <Route path="/settings/users" element={<h1>Users page</h1>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => vi.clearAllMocks())

describe('AuthenticatedLayout', () => {
  it('hidesNavigationWithoutReadPermission', () => {
    renderLayout(['USER_READ'])

    expect(screen.getByRole('menuitem', { name: 'Users' })).toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: 'Projects' })).not.toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: 'Roles' })).not.toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: 'Teams' })).not.toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: 'Cost centers' })).not.toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: 'Provider accounts' })).not.toBeInTheDocument()
  })

  it('shows every settings item with all read permissions', () => {
    renderLayout(['USER_READ', 'ROLE_READ', 'PROJECT_READ', 'TEAM_READ', 'COST_CENTER_READ', 'PROVIDER_ACCOUNT_READ'])

    for (const label of ['Users', 'Roles', 'Projects', 'Teams', 'Cost centers', 'Provider accounts']) {
      expect(screen.getByRole('menuitem', { name: label })).toBeInTheDocument()
    }
  })

  it('renders the protected page outlet', () => {
    renderLayout(['USER_READ'])

    expect(screen.getByRole('heading', { name: 'Users page' })).toBeInTheDocument()
  })

  it('keeps logout available', () => {
    renderLayout([])

    fireEvent.click(screen.getByRole('button', { name: 'Sign out' }))
    expect(mockedUseAuth.mock.results[0].value.logout).toHaveBeenCalledTimes(1)
  })
})
