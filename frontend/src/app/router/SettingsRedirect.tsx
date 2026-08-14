import { Navigate } from 'react-router-dom'
import { useAuth } from '../../features/auth/AuthSessionProvider'
import { hasPermission, SETTINGS_NAV } from '../../features/settings/permissions'
import { ForbiddenPage } from './ForbiddenPage'

/**
 * Redirects the /settings (and legacy /app) roots to the first settings route
 * the current user may read, in the frozen priority order of SETTINGS_NAV.
 * With no settings READ permission it renders the authenticated Forbidden
 * page instead of redirecting to /login.
 */
export function SettingsRedirect() {
  const auth = useAuth()
  const first = SETTINGS_NAV.find((entry) => hasPermission(auth.user?.permissions, entry.readPermission))
  if (!first) return <ForbiddenPage />
  return <Navigate to={first.path} replace />
}
