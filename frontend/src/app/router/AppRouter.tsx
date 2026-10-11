import { lazy, Suspense } from 'react'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { useAuth } from '../../features/auth/AuthSessionProvider'
import { ForgotPasswordPage, InvitationPage, LoginPage, RegisterPage, ResetPasswordPage } from '../../features/auth/AuthPages'
import { AuthenticatedLayout } from '../layout/AuthenticatedLayout'
import { ApplicationLanding } from './ApplicationLanding'
import { ProtectedRoute } from './ProtectedRoute'
import { PublicRoute } from './PublicRoute'
import { PermissionRoute } from './PermissionRoute'
import { SettingsRedirect } from './SettingsRedirect'
import { WorkbenchPage } from '../../features/workbench/WorkbenchPage'

/* Route-level code splitting: every feature page is a lazy chunk so the
   initial bundle carries only the shell, auth pages and the workbench.
   Auth pages stay eager — they are the first paint for signed-out users.
   The Suspense boundary lives in AuthenticatedLayout around <Outlet />, so
   the sidebar stays put while a page chunk loads. */
const EvidenceDetailPage = lazy(() => import('../../features/evidence/EvidenceDetailPage').then((m) => ({ default: m.EvidenceDetailPage })))
const EvidenceListPage = lazy(() => import('../../features/evidence/EvidenceListPage').then((m) => ({ default: m.EvidenceListPage })))
const ImportDetailPage = lazy(() => import('../../features/imports/ImportDetailPage').then((m) => ({ default: m.ImportDetailPage })))
const ImportListPage = lazy(() => import('../../features/imports/ImportListPage').then((m) => ({ default: m.ImportListPage })))
const CostDetailPage = lazy(() => import('../../features/costs/CostDetailPage').then((m) => ({ default: m.CostDetailPage })))
const CostsListPage = lazy(() => import('../../features/costs/CostsListPage').then((m) => ({ default: m.CostsListPage })))
const DuplicatesPage = lazy(() => import('../../features/duplicates/DuplicatesPage').then((m) => ({ default: m.DuplicatesPage })))
const RulesPage = lazy(() => import('../../features/allocation-rules/RulesPage').then((m) => ({ default: m.RulesPage })))
const RolesPage = lazy(() => import('../../features/settings/roles/RolesPage').then((m) => ({ default: m.RolesPage })))
const ProjectsPage = lazy(() => import('../../features/settings/projects/ProjectsPage').then((m) => ({ default: m.ProjectsPage })))
const TeamsPage = lazy(() => import('../../features/settings/teams/TeamsPage').then((m) => ({ default: m.TeamsPage })))
const CostCentersPage = lazy(() => import('../../features/settings/costCenters/CostCentersPage').then((m) => ({ default: m.CostCentersPage })))
const ProviderAccountsPage = lazy(() => import('../../features/settings/providerAccounts/ProviderAccountsPage').then((m) => ({ default: m.ProviderAccountsPage })))
const ProviderGalleryPage = lazy(() => import('../../features/provider/ProviderGalleryPage').then((m) => ({ default: m.ProviderGalleryPage })))
const ProviderConnectionsPage = lazy(() => import('../../features/provider/ProviderConnectionsPage').then((m) => ({ default: m.ProviderConnectionsPage })))
const ConnectionDetailPage = lazy(() => import('../../features/provider/ConnectionDetailPage').then((m) => ({ default: m.ConnectionDetailPage })))
const ProviderModelsPage = lazy(() => import('../../features/provider/ProviderModelsPage').then((m) => ({ default: m.ProviderModelsPage })))
const UsersPage = lazy(() => import('../../features/settings/users/UsersPage').then((m) => ({ default: m.UsersPage })))
const ExpensesListPage = lazy(() => import('../../features/expenses/ExpensesListPage').then((m) => ({ default: m.ExpensesListPage })))
const ExpensesNewPage = lazy(() => import('../../features/expenses/ExpensesNewPage').then((m) => ({ default: m.ExpensesNewPage })))
const ExpenseDetailPage = lazy(() => import('../../features/expenses/ExpenseDetailPage').then((m) => ({ default: m.ExpenseDetailPage })))
const ExpenseReviewQueuePage = lazy(() => import('../../features/expenses/ExpenseReviewQueuePage').then((m) => ({ default: m.ExpenseReviewQueuePage })))
const ExpenseReviewDetailPage = lazy(() => import('../../features/expenses/ExpenseReviewDetailPage').then((m) => ({ default: m.ExpenseReviewDetailPage })))
const BudgetsListPage = lazy(() => import('../../features/budgets/BudgetsListPage').then((m) => ({ default: m.BudgetsListPage })))
const BudgetDetailPage = lazy(() => import('../../features/budgets/BudgetDetailPage').then((m) => ({ default: m.BudgetDetailPage })))
const BudgetCommitmentDetailPage = lazy(() => import('../../features/budgets/BudgetCommitmentDetailPage').then((m) => ({ default: m.BudgetCommitmentDetailPage })))
const LedgerListPage = lazy(() => import('../../features/ledger/LedgerListPage').then((m) => ({ default: m.LedgerListPage })))
const LedgerPostingDetailPage = lazy(() => import('../../features/ledger/LedgerPostingDetailPage').then((m) => ({ default: m.LedgerPostingDetailPage })))
const LedgerEntryDetailPage = lazy(() => import('../../features/ledger/LedgerEntryDetailPage').then((m) => ({ default: m.LedgerEntryDetailPage })))
const ReconciliationCaseDetailPage = lazy(() => import('../../features/reconciliation/ReconciliationCaseDetailPage').then((m) => ({ default: m.ReconciliationCaseDetailPage })))
const ReconciliationPage = lazy(() => import('../../features/reconciliation/ReconciliationPage').then((m) => ({ default: m.ReconciliationPage })))
const ReconciliationRunDetailPage = lazy(() => import('../../features/reconciliation/ReconciliationRunDetailPage').then((m) => ({ default: m.ReconciliationRunDetailPage })))
const PeriodClosePage = lazy(() => import('../../features/period-close/PeriodClosePage').then((m) => ({ default: m.PeriodClosePage })))
const RoutingPoliciesPage = lazy(() => import('../../features/settings/routingPolicies/RoutingPoliciesPage').then((m) => ({ default: m.RoutingPoliciesPage })))
const ServiceIdentitiesPage = lazy(() => import('../../features/gateway/ServiceIdentitiesPage').then((m) => ({ default: m.ServiceIdentitiesPage })))
const GatewayCredentialsPage = lazy(() => import('../../features/gateway/GatewayCredentialsPage').then((m) => ({ default: m.GatewayCredentialsPage })))
const ModelPricingPage = lazy(() => import('../../features/gateway/ModelPricingPage').then((m) => ({ default: m.ModelPricingPage })))
const OverviewPage = lazy(() => import('../../features/intelligence/OverviewPage').then((m) => ({ default: m.OverviewPage })))
const AnomaliesPage = lazy(() => import('../../features/intelligence/AnomaliesPage').then((m) => ({ default: m.AnomaliesPage })))
const ForecastsPage = lazy(() => import('../../features/intelligence/ForecastsPage').then((m) => ({ default: m.ForecastsPage })))
const SavingsPage = lazy(() => import('../../features/intelligence/SavingsPage').then((m) => ({ default: m.SavingsPage })))
const AdvisorPage = lazy(() => import('../../features/advisor/AdvisorPage').then((m) => ({ default: m.AdvisorPage })))

const DevV3Overview = import.meta.env.DEV
  ? lazy(() => import('../../features/intelligence/benchmark/V3OverviewBenchmark').then((m) => ({ default: m.V3OverviewBenchmark })))
  : null
const DevV3Anomalies = import.meta.env.DEV
  ? lazy(() => import('../../features/intelligence/benchmark/V3WorkspaceBenchmark').then((m) => ({ default: m.V3AnomaliesBenchmark })))
  : null
const DevV3Forecasts = import.meta.env.DEV
  ? lazy(() => import('../../features/intelligence/benchmark/V3WorkspaceBenchmark').then((m) => ({ default: m.V3ForecastsBenchmark })))
  : null
const DevV3Savings = import.meta.env.DEV
  ? lazy(() => import('../../features/intelligence/benchmark/V3WorkspaceBenchmark').then((m) => ({ default: m.V3SavingsBenchmark })))
  : null
const DevV3Gallery = import.meta.env.DEV
  ? lazy(() => import('../../features/provider/benchmark/V3ProviderBenchmark').then((m) => ({ default: m.V3GalleryBenchmark })))
  : null
const DevV3Detail = import.meta.env.DEV
  ? lazy(() => import('../../features/provider/benchmark/V3ProviderBenchmark').then((m) => ({ default: m.V3ConnectionDetailBenchmark })))
  : null
const DevV3Models = import.meta.env.DEV
  ? lazy(() => import('../../features/provider/benchmark/V3ProviderBenchmark').then((m) => ({ default: m.V3ModelsBenchmark })))
  : null
const DevV3AdvisorCompleted = import.meta.env.DEV
  ? lazy(() => import('../../features/advisor/benchmark/V3AdvisorBenchmark').then((m) => ({ default: m.V3AdvisorCompletedBenchmark })))
  : null
const DevV3AdvisorRunning = import.meta.env.DEV
  ? lazy(() => import('../../features/advisor/benchmark/V3AdvisorBenchmark').then((m) => ({ default: m.V3AdvisorRunningBenchmark })))
  : null
const DevV3AdvisorFailed = import.meta.env.DEV
  ? lazy(() => import('../../features/advisor/benchmark/V3AdvisorBenchmark').then((m) => ({ default: m.V3AdvisorFailedBenchmark })))
  : null

export function AppRouter() {
  const auth = useAuth()
  if (auth.status === 'loading') return <main className="auth-page" role="status">正在恢复会话…</main>
  return <BrowserRouter><Routes>
    <Route element={<PublicRoute />}>
      <Route path="/login" element={<LoginPage />} /><Route path="/register" element={<RegisterPage />} />
      <Route path="/forgot-password" element={<ForgotPasswordPage />} /><Route path="/reset-password" element={<ResetPasswordPage />} />
      <Route path="/invite/:token" element={<InvitationPage />} />
    </Route>
    {DevV3Overview && <Route path="/dev/v3-overview" element={<Suspense fallback={<main className="auth-page" role="status">正在加载预览…</main>}><DevV3Overview /></Suspense>} />}
    {DevV3Anomalies && <Route path="/dev/v3-anomalies" element={<Suspense fallback={null}><DevV3Anomalies /></Suspense>} />}
    {DevV3Forecasts && <Route path="/dev/v3-forecasts" element={<Suspense fallback={null}><DevV3Forecasts /></Suspense>} />}
    {DevV3Savings && <Route path="/dev/v3-savings" element={<Suspense fallback={null}><DevV3Savings /></Suspense>} />}
    {DevV3Gallery && <Route path="/dev/v3-gallery" element={<Suspense fallback={null}><DevV3Gallery /></Suspense>} />}
    {DevV3Detail && <Route path="/dev/v3-connection-detail" element={<Suspense fallback={null}><DevV3Detail /></Suspense>} />}
    {DevV3Models && <Route path="/dev/v3-models" element={<Suspense fallback={null}><DevV3Models /></Suspense>} />}
    {DevV3AdvisorCompleted && <Route path="/dev/v3-advisor-completed" element={<Suspense fallback={null}><DevV3AdvisorCompleted /></Suspense>} />}
    {DevV3AdvisorRunning && <Route path="/dev/v3-advisor-running" element={<Suspense fallback={null}><DevV3AdvisorRunning /></Suspense>} />}
    {DevV3AdvisorFailed && <Route path="/dev/v3-advisor-failed" element={<Suspense fallback={null}><DevV3AdvisorFailed /></Suspense>} />}
    <Route element={<ProtectedRoute isAuthenticated={auth.status === 'authenticated'} />}>
      <Route element={<AuthenticatedLayout />}>
        <Route path="/workbench" element={<WorkbenchPage />} />
        <Route path="/intelligence/overview" element={<PermissionRoute permission="COST_READ" />}><Route index element={<OverviewPage />} /></Route>
        <Route path="/intelligence/anomalies" element={<PermissionRoute permission="COST_READ" />}><Route index element={<AnomaliesPage />} /></Route>
        <Route path="/intelligence/forecasts" element={<PermissionRoute permission="COST_READ" />}><Route index element={<ForecastsPage />} /></Route>
        <Route path="/intelligence/savings" element={<PermissionRoute permission="COST_READ" />}><Route index element={<SavingsPage />} /></Route>
        <Route path="/advisor" element={<PermissionRoute permission="AI_ADVISOR_USE" />}><Route index element={<AdvisorPage />} /></Route>
        <Route path="/evidence" element={<PermissionRoute permission="EVIDENCE_READ" />}>
          <Route index element={<EvidenceListPage />} />
          <Route path=":id" element={<EvidenceDetailPage />} />
        </Route>
        <Route path="/imports" element={<PermissionRoute permission="IMPORT_READ" />}>
          <Route index element={<ImportListPage />} />
          <Route path=":id" element={<ImportDetailPage />} />
        </Route>
        <Route path="/costs" element={<PermissionRoute permission="COST_READ" />}>
          <Route index element={<CostsListPage />} />
          <Route path=":id" element={<CostDetailPage />} />
          <Route path="duplicates" element={<PermissionRoute permission="DUPLICATE_REVIEW" />}>
            <Route index element={<DuplicatesPage />} />
          </Route>
        </Route>
        <Route path="/allocation-rules" element={<PermissionRoute permission="ALLOCATION_RULE_MANAGE" />}>
          <Route index element={<RulesPage />} />
        </Route>
        <Route path="/expenses" element={<PermissionRoute permission="EXPENSE_READ_OWN" />}>
          <Route index element={<ExpensesListPage />} />
          <Route path="new" element={<ExpensesNewPage />} />
          <Route path=":expenseId" element={<ExpenseDetailPage />} />
        </Route>
        <Route path="/expense-reviews" element={<PermissionRoute permission="EXPENSE_REVIEW" />}>
          <Route index element={<ExpenseReviewQueuePage />} />
          <Route path=":expenseId" element={<ExpenseReviewDetailPage />} />
        </Route>
        <Route path="/budgets" element={<PermissionRoute permission="BUDGET_READ" />}>
          <Route index element={<BudgetsListPage />} />
          <Route path=":budgetId" element={<BudgetDetailPage />} />
        </Route>
        <Route path="/budget-commitments/:commitmentId" element={<PermissionRoute permission="BUDGET_READ" />}>
          <Route index element={<BudgetCommitmentDetailPage />} />
        </Route>
        <Route path="/ledger" element={<PermissionRoute permission="LEDGER_READ" />}>
          <Route index element={<LedgerListPage />} />
          <Route path="postings/:id" element={<LedgerPostingDetailPage />} />
          <Route path="entries/:id" element={<LedgerEntryDetailPage />} />
        </Route>
        <Route path="/reconciliation" element={<PermissionRoute permission="RECONCILIATION_READ" />}>
          <Route index element={<ReconciliationPage />} />
          <Route path="cases/:caseId" element={<ReconciliationCaseDetailPage />} />
          <Route path=":runId" element={<ReconciliationRunDetailPage />} />
        </Route>
        <Route path="/period-close" element={<PermissionRoute permission="PERIOD_READ" />}>
          <Route index element={<PeriodClosePage />} />
          <Route path=":periodId" element={<PeriodClosePage />} />
        </Route>
        <Route path="/settings/users" element={<PermissionRoute permission="USER_READ" />}><Route index element={<UsersPage />} /></Route>
        <Route path="/settings/roles" element={<PermissionRoute permission="ROLE_READ" />}><Route index element={<RolesPage />} /></Route>
        <Route path="/settings/projects" element={<PermissionRoute permission="PROJECT_READ" />}><Route index element={<ProjectsPage />} /></Route>
        <Route path="/settings/teams" element={<PermissionRoute permission="TEAM_READ" />}><Route index element={<TeamsPage />} /></Route>
        <Route path="/settings/cost-centers" element={<PermissionRoute permission="COST_CENTER_READ" />}><Route index element={<CostCentersPage />} /></Route>
        <Route path="/settings/provider-accounts" element={<PermissionRoute permission="PROVIDER_ACCOUNT_READ" />}><Route index element={<ProviderAccountsPage />} /></Route>
        <Route path="/settings/providers" element={<PermissionRoute permission="PROVIDER_ACCOUNT_READ" />}><Route index element={<ProviderGalleryPage />} /></Route>
        <Route path="/settings/provider-connections" element={<PermissionRoute permission="PROVIDER_ACCOUNT_READ" />}><Route index element={<ProviderConnectionsPage />} /></Route>
        <Route path="/settings/provider-connections/:id" element={<PermissionRoute permission="PROVIDER_ACCOUNT_READ" />}><Route index element={<ConnectionDetailPage />} /></Route>
        <Route path="/settings/provider-models" element={<PermissionRoute permission="PROVIDER_ACCOUNT_READ" />}><Route index element={<ProviderModelsPage />} /></Route>
        <Route path="/settings/routing-policies" element={<PermissionRoute permission="PROVIDER_ACCOUNT_READ" />}><Route index element={<RoutingPoliciesPage />} /></Route>
        <Route path="/settings/service-identities" element={<PermissionRoute permission="PROVIDER_ACCOUNT_READ" />}><Route index element={<ServiceIdentitiesPage />} /></Route>
        <Route path="/settings/gateway-credentials" element={<PermissionRoute permission="PROVIDER_ACCOUNT_READ" />}><Route index element={<GatewayCredentialsPage />} /></Route>
        <Route path="/settings/model-pricing" element={<PermissionRoute permission="PROVIDER_ACCOUNT_READ" />}><Route index element={<ModelPricingPage />} /></Route>
        <Route path="/settings" element={<SettingsRedirect />} />
      </Route>
      <Route path="/app" element={<ApplicationLanding />} />
    </Route>
    <Route path="*" element={<Navigate to={auth.status === 'authenticated' ? '/app' : '/login'} replace />} />
  </Routes></BrowserRouter>
}
