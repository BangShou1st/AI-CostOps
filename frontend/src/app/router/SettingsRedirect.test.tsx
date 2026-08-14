import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from '../../features/auth/AuthSessionProvider'
import { SettingsRedirect } from './SettingsRedirect'

vi.mock('../../features/auth/AuthSessionProvider', () => ({ useAuth: vi.fn() }))

const mockedUseAuth = vi.mocked(useAuth)

function renderSettingsRoot(permissions: string[]) {
  mockedUseAuth.mockReturnValue({
    status: 'authenticated',
    user: { id: '1', email: 'admin@example.com', displayName: 'Admin', organizationId: '2', organizationMemberId: '3', permissions },
    login: vi.fn(),
    refreshMe: vi.fn(),
    logout: vi.fn(),
  } as ReturnType<typeof useAuth>)
  render(
    <MemoryRouter initialEntries={['/settings']}>
      <Routes>
        <Route path="/settings" element={<SettingsRedirect />} />
        <Route path="/settings/users" element={<h1>Users page</h1>} />
        <Route path="/settings/projects" element={<h1>Projects page</h1>} />
        <Route path="/login" element={<h1>Sign in page</h1>} />
      </Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => vi.clearAllMocks())

describe('SettingsRedirect', () => {
  it('settingsRootRedirectsToFirstPermittedRoute', () => {
    // Without USER_READ the first permitted route is Projects.
    renderSettingsRoot(['PROJECT_READ', 'TEAM_READ'])

    expect(screen.getByRole('heading', { name: 'Projects page' })).toBeInTheDocument()
  })

  it('settingsRootPrefersUsersWhenUserReadPresent', () => {
    renderSettingsRoot(['USER_READ', 'PROJECT_READ'])

    expect(screen.getByRole('heading', { name: 'Users page' })).toBeInTheDocument()
  })

  it('settingsRootRendersForbiddenWhenNoReadPermission', () => {
    renderSettingsRoot([])

    expect(screen.getByRole('heading', { name: '403' })).toBeInTheDocument()
    // Never redirects to sign in.
    expect(screen.queryByRole('heading', { name: 'Sign in page' })).not.toBeInTheDocument()
  })
})
