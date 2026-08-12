import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { accessTokenStore } from '../../features/auth/accessTokenStore'
import { ProtectedRoute } from './ProtectedRoute'
import { PublicRoute } from './PublicRoute'

function FoundationPage() {
  return (
    <main className="app-shell">
      <p className="eyebrow">M0 · Repository Foundation</p>
      <h1>AI CostOps</h1>
      <p>Buildable, testable, runnable monorepo foundation.</p>
    </main>
  )
}

function ProtectedFoundationPage() {
  return <main className="app-shell">Protected application foundation</main>
}

export function AppRouter() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<PublicRoute />}>
          <Route path="/" element={<FoundationPage />} />
        </Route>
        <Route element={<ProtectedRoute isAuthenticated={accessTokenStore.get() !== null} />}>
          <Route path="/app" element={<ProtectedFoundationPage />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}
