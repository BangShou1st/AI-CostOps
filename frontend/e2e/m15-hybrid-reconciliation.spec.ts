import { expect, test } from '@playwright/test'
import { ApiClient, deepSeekTwoCostFixtureZip } from './support/api'
import { loginAsAdmin } from './support/auth'
import { ADMIN_EMAIL, ADMIN_PASSWORD, E2E_BASE_URL, currentBusinessDates, uniqueSuffix } from './support/env'
import { pickAntdSelect } from './support/ui'

/**
 * M15 hybrid reconciliation browser acceptance. The prerequisite books are
 * created through the public API (confirmed provider statement with two CNY
 * charges; only the first is allocated and posted, leaving a real aggregate
 * provider/currency difference). The M15 workflow itself is then driven in
 * the browser:
 *
 * - Scenario B: the case detail separates whole-case actions (CASE_FULL
 *   adjustment, showing the server-required difference) from evidence-item
 *   actions, and the aggregate evidence is attached to the case.
 * - The CASE_FULL adjustment posts with explicit allocation lines only; the
 *   server-required amount is displayed and never invented by the client.
 * - Scenario E: after the books settle and the period closes, the case detail
 *   shows the CLOSED-period banner; reconciliation never reopens history, and
 *   the explicit governed reopen restores the OPEN period.
 *
 * Scenarios A/D (run-level GATEWAY_UNRESOLVED work and statement-backed
 * gateway resolutions) require durable Gateway request facts, which no public
 * E2E surface can create; they are proven against real MySQL by the backend
 * integration suites (GatewayFinancialResolutionIntegrationTest and the M15
 * race matrix).
 */
test.describe('m15 hybrid reconciliation', () => {
  test('case-scoped hybrid workflow: case attachment, CASE_FULL adjustment, closed banner', async ({ page, request }) => {
    const api = new ApiClient(request)
    const auth = await api.login(ADMIN_EMAIL, ADMIN_PASSWORD)
    const token = auth.accessToken
    const suffix = uniqueSuffix()
    const dates = currentBusinessDates()

    let caseId = 0
    let differenceAmount = ''
    let smallChargeId = 0
    let myProviderAccountId = 0

    await test.step('seed: provider account, project and a two-cost confirmed statement', async () => {
      const provider = await api.createProviderAccount(token, {
        providerCode: 'DEEPSEEK',
        displayName: `M15 E2E ${suffix}`,
        externalAccountRef: `m15-${suffix}`,
      })
      const project = await api.createProject(token, {
        name: `m15-e2e-${suffix}`,
        code: `m15-e2e-${suffix}`,
      })
      // Charge selection must be robust against leftovers from earlier spec
      // runs in the same organization: only charges created by this import.
      const chargesBefore = new Set(
        (await api.listCharges(token)).items.map((charge) => charge.id),
      )
      const zip = deepSeekTwoCostFixtureZip(dates.month, dates.startIso, dates.endIso, suffix)
      const created = await api.uploadProviderImport(token, zip, provider.id)
      const importBatchId = Number(created.importBatchId)
      expect(importBatchId).toBeTruthy()
      await expect.poll(
        async () => (await api.getImport(token, importBatchId)).status,
        { timeout: 120_000 },
      ).toBe('READY_FOR_REVIEW')
      await api.confirmImport(token, importBatchId)

      const charges = (await api.listCharges(token)).items.filter(
        (charge) => !chargesBefore.has(charge.id) && charge.currency === 'CNY'
          && (charge.amount === '10.00000000' || charge.amount === '4.00000000'),
      )
      expect(charges.length).toBe(2)
      const bigCharge = charges.find((charge) => charge.amount === '10.00000000')!
      const smallCharge = charges.find((charge) => charge.amount === '4.00000000')!
      smallChargeId = smallCharge.id

      // Only the first charge becomes internal truth: allocate fully, post.
      const decision = await api.createManualAllocation(
        token,
        { chargeId: bigCharge.id },
        [{ allocatedAmount: '10.00000000', currency: 'CNY', projectId: project.id }],
      )
      await api.confirmAllocation(token, decision.id)
      await api.postCharge(token, bigCharge.id)
    })

    await test.step('run reconciliation: aggregate case exists with attached evidence', async () => {
      const periods = await api.listBillingPeriods(token)
      const openPeriod = periods.find((period) => period.status === 'OPEN')
      expect(openPeriod).toBeTruthy()
      const run = await api.runReconciliation(token, openPeriod!.id)
      const cases = await api.listReconciliationCases(token, { runId: run.id })
      const mismatch = cases.items.find((entry) => (entry as { caseType?: string }).caseType === 'AMOUNT_MISMATCH')
      expect(mismatch, 'the partial posting must create an aggregate case').toBeTruthy()
      caseId = Number(mismatch!.id)

      // One aggregate case hosts the evidence of its scope: the
      // AGGREGATE_SCOPE evidence row carries this case id.
      const evidence = await api.listRunReconciliationEvidence(token, run.id)
      const aggregateRows = evidence.items.filter((row) => row.matchKind === 'AGGREGATE_SCOPE')
      expect(aggregateRows.length).toBeGreaterThan(0)
      // The evidence of this case's provider/currency scope must be attached
      // to this case (other scopes may carry their own cases in a shared E2E
      // organization).
      expect(aggregateRows.some((row) => Number(row.reconciliationCaseId) === caseId)).toBe(true)

      myProviderAccountId = Number(mismatch!.providerAccountId)
      const caseDetail = await api.getCaseDetail(token, caseId)
      differenceAmount = caseDetail.differenceAmount
      expect(differenceAmount).not.toBe('0.00000000')
    })

    await test.step('browser: whole-case vs evidence-item separation and CASE_FULL adjustment', async () => {
      await loginAsAdmin(page)
      await page.goto(`${E2E_BASE_URL}/reconciliation/cases/${caseId}`)
      await expect(page.getByRole('heading', { name: '对账案例详情', level: 1 })).toBeVisible({ timeout: 20_000 })

      // The whole-case action area shows the server-required difference.
      await expect(page.getByText('整体案例操作')).toBeVisible()
      await expect(page.getByText(/服务端要求的调整金额/)).toBeVisible()
      // Evidence-item actions stay separate and never claim to resolve the case.
      await expect(page.getByText('混合证据与单条证据操作')).toBeVisible()
      await expect(page.getByText('处理单条证据不会自动解决同案例下的其他证据')).toBeVisible()

      // Investigate, then post the CASE_FULL adjustment with an explicit line.
      await page.getByRole('button', { name: '开始调查' }).click()
      await expect(page.getByRole('button', { name: '提交 CASE_FULL 调整' })).toBeVisible({ timeout: 20_000 })
      await page.getByRole('button', { name: '提交 CASE_FULL 调整' }).click()

      const modal = page.locator('.ant-modal:visible').last()
      await expect(modal.getByText(/服务端要求金额/)).toBeVisible()
      await pickAntdSelect(page, '调整入账账期', /#/)
      // The allocation target picker is the search-enabled select inside the
      // Space.Compact pair (type select is not searchable).
      await modal.locator('.ant-select-show-search').click()
      await page.locator('.ant-select-dropdown:visible').last()
        .locator('.ant-select-item-option')
        .filter({ hasText: /m15-e2e-/ })
        .first()
        .click()
      // The required adjustment is external - internal: the exact negation
      // of the canonical difference shown on the case.
      const requiredAmount = differenceAmount.startsWith('-')
        ? differenceAmount.slice(1)
        : '-' + differenceAmount
      await modal.locator('input[aria-label="分配行金额"]').fill(requiredAmount)
      await modal.locator('input[aria-label="调整原因代码"]').fill('M15_E2E_RESOLVED')
      await modal.locator('textarea[aria-label="调整说明"]').fill('Resolved by the M15 E2E drill')
      await modal.getByRole('button', { name: '确认提交' }).click()
      await expect(modal).toBeHidden({ timeout: 20_000 }).catch(async () => {
        console.log('DBG_PAGE=' + (await page.locator('body').innerText()).slice(0, 1500))
        throw new Error('modal stayed open')
      })
      // The whole-case financial action marks this historical case resolved.
      await expect(page.getByText('已解决').first()).toBeVisible({ timeout: 20_000 })
    })

    await test.step('settle the books and rerun reconciliation', async () => {
      expect(smallChargeId).toBeTruthy()
      const targets = await api.listAllocationTargets(token)
      const project = targets.find((entry) => entry.type === 'PROJECT' && entry.name.startsWith('m15-e2e-'))
      expect(project).toBeTruthy()
      // The CASE_FULL adjustment above already brought the internal truth to
      // the statement total, so this charge must only receive a CONFIRMED
      // allocation (clearing UNALLOCATED_CHARGES); posting it again would
      // double count the same real cost.
      const decision = await api.createManualAllocation(
        token,
        { chargeId: smallChargeId },
        [{ allocatedAmount: '4.00000000', currency: 'CNY', projectId: Number(project!.id) }],
      )
      await api.confirmAllocation(token, decision.id)

      const periods = await api.listBillingPeriods(token)
      const openPeriod = periods.find((period) => period.status === 'OPEN')
      const rerun = await api.runReconciliation(token, openPeriod!.id)
      const rerunCases = await api.listReconciliationCases(token, { runId: rerun.id })
      // The E2E organization is shared between specs; only this spec's own
      // provider/currency scope must be clean here: after the adjustment and
      // the allocation the difference is zero, so the rerun produces no open
      // case for this scope (a zero difference never fabricates a case).
      const ownScopeCases = rerunCases.items.filter(
        (entry) => Number((entry as { providerAccountId?: string }).providerAccountId)
          === myProviderAccountId,
      )
      expect(ownScopeCases.every((entry) => entry.status === 'RESOLVED')).toBe(true)

      // The shared E2E organization may still carry unresolved cases of other
      // specs' scopes; resolve them explicitly (the same non-financial
      // ACCEPT_EXPLAINED_DIFFERENCE path the close drill uses) so the period
      // can close.
      const leftovers = rerunCases.items.filter((entry) => entry.status !== 'RESOLVED')
      for (const leftover of leftovers) {
        await api.investigateCase(token, Number(leftover.id))
        await api.resolveCase(token, Number(leftover.id), {
          reasonCode: 'OPERATIONAL_DECISION',
          resolutionNote: 'Resolved by the M15 E2E drill',
        })
      }
    })

    await test.step('close the period and prove the CLOSED banner without auto reopen', async () => {
      const periods = await api.listBillingPeriods(token)
      const openPeriod = periods.find((period) => period.status === 'OPEN')
      const readiness = await api.closeReadiness(token, openPeriod!.id)
      expect(readiness.ready, JSON.stringify(readiness.checks)).toBe(true)
      const closed = await api.closePeriod(token, openPeriod!.id)
      expect(closed.periodStatus).toBe('CLOSED')

      // The closed-period case shows the banner; reconciliation never reopens
      // history on its own.
      await page.goto(`${E2E_BASE_URL}/reconciliation/cases/${caseId}`)
      await expect(page.getByRole('heading', { name: '对账案例详情', level: 1 })).toBeVisible({ timeout: 20_000 })
      await expect(page.getByText('该账期已关闭')).toBeVisible({ timeout: 20_000 })
      await expect(page.getByText('对账仅作证据查阅，不会自动重开历史账期')).toBeVisible()

      // Restore the OPEN period through the explicit governed reopen so the
      // following E2E specs keep their own open-period assumptions.
      await api.reopenPeriod(token, openPeriod!.id, {
        reasonCode: 'M15_E2E_REOPEN',
        reasonNote: 'Restore the open period after the M15 E2E drill',
      })
      const restored = (await api.listBillingPeriods(token)).find(
        (period) => period.id === openPeriod!.id,
      )
      expect(restored?.status).toBe('OPEN')
    })
  })
})
