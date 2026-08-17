import { Navigate } from 'react-router-dom'
import { useAuth } from '../../features/auth/AuthSessionProvider'
import { hasPermission, SETTINGS_NAV } from '../../features/settings/permissions'
import { ForbiddenPage } from './ForbiddenPage'

/**
 * Business-aware application landing for /app (and the authenticated
 * wildcard): the first readable business route wins (Evidence before Imports),
 * then the first permitted settings route; with no read permission at all it
 * renders the authenticated Forbidden page instead of redirecting to /login.
 */
export function ApplicationLanding() {
  const auth = useAuth()
  const permissions = auth.user?.permissions
  if (hasPermission(permissions, 'EVIDENCE_READ')) {
    return <Navigate to="/evidence" replace />
  }
  if (hasPermission(permissions, 'IMPORT_READ')) {
    return <Navigate to="/imports" replace />
  }
  if (hasPermission(permissions, 'COST_READ')) {
    return <Navigate to="/costs" replace />
  }
  if (hasPermission(permissions, 'ALLOCATION_RULE_MANAGE')) {
    return <Navigate to="/allocation-rules" replace />
  }
  if (hasPermission(permissions, 'EXPENSE_REVIEW')) {
    return <Navigate to="/expense-reviews" replace />
  }
  if (hasPermission(permissions, 'EXPENSE_READ_OWN')) {
    return <Navigate to="/expenses" replace />
  }
  const first = SETTINGS_NAV.find((entry) => hasPermission(permissions, entry.readPermission))
  if (!first) return <ForbiddenPage />
  return <Navigate to={first.path} replace />
}
