import { Navigate } from 'react-router-dom'
import { useAuth } from '../../features/auth/AuthSessionProvider'
import { hasPermission, SETTINGS_NAV } from '../../features/settings/permissions'
import { hasWorkbenchAccess } from '../layout/appNavigation'
import { ForbiddenPage } from './ForbiddenPage'

/**
 * Business-aware application landing for /app (and the authenticated
 * wildcard): the workbench wins whenever the caller holds any ORG section
 * grant, then the first readable business route, then the first permitted
 * settings route; with no read permission at all it renders the
 * authenticated Forbidden page instead of redirecting to /login.
 */
export function ApplicationLanding() {
  const auth = useAuth()
  const permissions = auth.user?.permissions
  if (hasWorkbenchAccess(permissions)) {
    return <Navigate to="/workbench" replace />
  }
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
  if (hasPermission(permissions, 'BUDGET_READ')) {
    return <Navigate to="/budgets" replace />
  }
  if (hasPermission(permissions, 'LEDGER_READ')) {
    return <Navigate to="/ledger" replace />
  }
  if (hasPermission(permissions, 'RECONCILIATION_READ')) {
    return <Navigate to="/reconciliation" replace />
  }
  if (hasPermission(permissions, 'PERIOD_READ')) {
    return <Navigate to="/period-close" replace />
  }
  const first = SETTINGS_NAV.find((entry) => hasPermission(permissions, entry.readPermission))
  if (!first) return <ForbiddenPage />
  return <Navigate to={first.path} replace />
}
