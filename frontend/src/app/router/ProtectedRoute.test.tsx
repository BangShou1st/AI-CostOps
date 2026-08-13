import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { ProtectedRoute } from './ProtectedRoute'

function renderProtectedRoute(isAuthenticated: boolean) {
  render(
    <MemoryRouter initialEntries={['/protected']}>
      <Routes>
        <Route path="/login" element={<h1>Sign in</h1>} />
        <Route element={<ProtectedRoute isAuthenticated={isAuthenticated} />}>
          <Route path="/protected" element={<h1>Protected foundation</h1>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

describe('ProtectedRoute', () => {
  it('redirects unauthenticated access to sign in', () => {
    renderProtectedRoute(false)

    expect(screen.getByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
  })

  it('renders protected content when an access token is available', () => {
    renderProtectedRoute(true)

    expect(screen.getByRole('heading', { name: 'Protected foundation' })).toBeInTheDocument()
  })
})
