import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from '../../features/auth/AuthSessionProvider'
import { PermissionRoute } from './PermissionRoute'

vi.mock('../../features/auth/AuthSessionProvider', () => ({ useAuth: vi.fn() }))

const mockedUseAuth = vi.mocked(useAuth)

function renderPermissionRoute(permissions: string[], initialPath = '/settings/users') {
  mockedUseAuth.mockReturnValue({
    status: 'authenticated',
    user: { id: '1', email: 'admin@example.com', displayName: 'Admin', organizationId: '2', organizationMemberId: '3', permissions },
    login: vi.fn(),
    refreshMe: vi.fn(),
    logout: vi.fn(),
  } as ReturnType<typeof useAuth>)
  render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route path="/login" element={<h1>Sign in</h1>} />
        <Route element={<PermissionRoute permission="USER_READ" />}>
          <Route path="/settings/users" element={<h1>Users page</h1>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => vi.clearAllMocks())

describe('PermissionRoute', () => {
  it('directUnauthorizedUrlRendersForbidden', () => {
    renderPermissionRoute([])

    expect(screen.getByRole('heading', { name: '403' })).toBeInTheDocument()
    expect(screen.getByText(/没有查看此页面的权限/i)).toBeInTheDocument()
    // No redirect to sign in and no anonymous redirect target.
    expect(screen.queryByRole('heading', { name: '登录' })).not.toBeInTheDocument()
  })

  it('unauthorizedRouteDoesNotMountChild', () => {
    const Child = vi.fn(() => <h1>Users page</h1>)
    mockedUseAuth.mockReturnValue({
      status: 'authenticated',
      user: { id: '1', email: 'admin@example.com', displayName: 'Admin', organizationId: '2', organizationMemberId: '3', permissions: [] },
      login: vi.fn(),
    refreshMe: vi.fn(),
      logout: vi.fn(),
    } as ReturnType<typeof useAuth>)
    render(
      <MemoryRouter initialEntries={['/settings/users']}>
        <Routes>
          <Route element={<PermissionRoute permission="USER_READ" />}>
            <Route path="/settings/users" element={<Child />} />
          </Route>
        </Routes>
      </MemoryRouter>,
    )

    expect(Child).not.toHaveBeenCalled()
    expect(screen.getByRole('heading', { name: '403' })).toBeInTheDocument()
  })

  it('authorizedRouteRendersChild', () => {
    renderPermissionRoute(['USER_READ'])

    expect(screen.getByRole('heading', { name: 'Users page' })).toBeInTheDocument()
  })
})
