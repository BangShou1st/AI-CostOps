import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { useAuth } from '../../features/auth/AuthSessionProvider'
import { AppPage, ForgotPasswordPage, InvitationPage, LoginPage, RegisterPage, ResetPasswordPage } from '../../features/auth/AuthPages'
import { ProtectedRoute } from './ProtectedRoute'
import { PublicRoute } from './PublicRoute'

export function AppRouter() {
  const auth = useAuth()
  if (auth.status === 'loading') return <main className="auth-page" role="status">Restoring your session…</main>
  return <BrowserRouter><Routes>
    <Route element={<PublicRoute />}>
      <Route path="/login" element={<LoginPage />} /><Route path="/register" element={<RegisterPage />} />
      <Route path="/forgot-password" element={<ForgotPasswordPage />} /><Route path="/reset-password" element={<ResetPasswordPage />} />
      <Route path="/invite/:token" element={<InvitationPage />} />
    </Route>
    <Route element={<ProtectedRoute isAuthenticated={auth.status === 'authenticated'} />}><Route path="/app" element={<AppPage />} /></Route>
    <Route path="*" element={<Navigate to={auth.status === 'authenticated' ? '/app' : '/login'} replace />} />
  </Routes></BrowserRouter>
}
