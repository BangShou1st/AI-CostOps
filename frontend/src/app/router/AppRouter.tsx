import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { useAuth } from '../../features/auth/AuthSessionProvider'
import { ForgotPasswordPage, InvitationPage, LoginPage, RegisterPage, ResetPasswordPage } from '../../features/auth/AuthPages'
import { RolesPage } from '../../features/settings/roles/RolesPage'
import { ProjectsPage } from '../../features/settings/projects/ProjectsPage'
import { TeamsPage } from '../../features/settings/teams/TeamsPage'
import { CostCentersPage } from '../../features/settings/costCenters/CostCentersPage'
import { ProviderAccountsPage } from '../../features/settings/providerAccounts/ProviderAccountsPage'
import { UsersPage } from '../../features/settings/users/UsersPage'
import { AuthenticatedLayout } from '../layout/AuthenticatedLayout'
import { ProtectedRoute } from './ProtectedRoute'
import { PublicRoute } from './PublicRoute'
import { PermissionRoute } from './PermissionRoute'
import { SettingsRedirect } from './SettingsRedirect'

export function AppRouter() {
  const auth = useAuth()
  if (auth.status === 'loading') return <main className="auth-page" role="status">正在恢复会话…</main>
  return <BrowserRouter><Routes>
    <Route element={<PublicRoute />}>
      <Route path="/login" element={<LoginPage />} /><Route path="/register" element={<RegisterPage />} />
      <Route path="/forgot-password" element={<ForgotPasswordPage />} /><Route path="/reset-password" element={<ResetPasswordPage />} />
      <Route path="/invite/:token" element={<InvitationPage />} />
    </Route>
    <Route element={<ProtectedRoute isAuthenticated={auth.status === 'authenticated'} />}>
      <Route element={<AuthenticatedLayout />}>
        <Route path="/settings/users" element={<PermissionRoute permission="USER_READ" />}>
          <Route index element={<UsersPage />} />
        </Route>
        <Route path="/settings/roles" element={<PermissionRoute permission="ROLE_READ" />}>
          <Route index element={<RolesPage />} />
        </Route>
        <Route path="/settings/projects" element={<PermissionRoute permission="PROJECT_READ" />}>
          <Route index element={<ProjectsPage />} />
        </Route>
        <Route path="/settings/teams" element={<PermissionRoute permission="TEAM_READ" />}>
          <Route index element={<TeamsPage />} />
        </Route>
        <Route path="/settings/cost-centers" element={<PermissionRoute permission="COST_CENTER_READ" />}>
          <Route index element={<CostCentersPage />} />
        </Route>
        <Route path="/settings/provider-accounts" element={<PermissionRoute permission="PROVIDER_ACCOUNT_READ" />}>
          <Route index element={<ProviderAccountsPage />} />
        </Route>
        <Route path="/settings" element={<SettingsRedirect />} />
      </Route>
      <Route path="/app" element={<SettingsRedirect />} />
    </Route>
    <Route path="*" element={<Navigate to={auth.status === 'authenticated' ? '/settings' : '/login'} replace />} />
  </Routes></BrowserRouter>
}
