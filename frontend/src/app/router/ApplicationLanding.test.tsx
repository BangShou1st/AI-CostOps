import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from '../../features/auth/AuthSessionProvider'
import { ApplicationLanding } from './ApplicationLanding'

vi.mock('../../features/auth/AuthSessionProvider', () => ({ useAuth: vi.fn() }))

const mockedUseAuth = vi.mocked(useAuth)

function renderAppRoot(permissions: string[]) {
  mockedUseAuth.mockReturnValue({
    status: 'authenticated',
    user: { id: '1', email: 'admin@example.com', displayName: 'Admin', organizationId: '2', organizationMemberId: '3', permissions },
    login: vi.fn(),
    refreshMe: vi.fn(),
    logout: vi.fn(),
  } as ReturnType<typeof useAuth>)
  render(
    <MemoryRouter initialEntries={['/app']}>
      <Routes>
        <Route path="/app" element={<ApplicationLanding />} />
        <Route path="/evidence" element={<h1>Evidence page</h1>} />
        <Route path="/imports" element={<h1>Imports page</h1>} />
        <Route path="/settings/users" element={<h1>Users page</h1>} />
        <Route path="/login" element={<h1>Sign in page</h1>} />
      </Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => vi.clearAllMocks())

describe('ApplicationLanding', () => {
  it('prefers the evidence business route with evidence read', () => {
    renderAppRoot(['EVIDENCE_READ', 'IMPORT_READ'])

    expect(screen.getByRole('heading', { name: 'Evidence page' })).toBeInTheDocument()
  })

  it('falls back to imports when only import read is present', () => {
    renderAppRoot(['IMPORT_READ'])

    expect(screen.getByRole('heading', { name: 'Imports page' })).toBeInTheDocument()
  })

  it('falls back to the first permitted settings route for settings-only roles', () => {
    renderAppRoot(['USER_READ'])

    expect(screen.getByRole('heading', { name: 'Users page' })).toBeInTheDocument()
  })

  it('renders the authenticated forbidden page with no read permissions at all', () => {
    renderAppRoot([])

    expect(screen.getByRole('heading', { name: '403' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Sign in page' })).not.toBeInTheDocument()
  })
})
