import { expect, test } from '@playwright/test'
import { ApiClient, type Expense } from './support/api'
import { loginAsAdmin } from './support/auth'
import { ADMIN_EMAIL, ADMIN_PASSWORD, E2E_BASE_URL, uniqueSuffix } from './support/env'
import { pickAntdSelect } from './support/ui'

/**
 * Reconciliation -> close -> CLOSED write rejection -> reopen. The close and
 * reopen transitions here are the behavior under test and are driven in the
 * browser. Before closing, the books are settled through the API (allocate
 * leftover charges, post leftover approved expenses) and the final
 * reconciliation run is also driven in the browser so the close readiness has
 * real user-visible reconciliation evidence behind it.
 */
test.describe('reconciliation and period close', () => {
  test('run reconciliation, close the period, reject closed writes, reopen', async ({ page, request }) => {
    const api = new ApiClient(request)
    const auth = await api.login(ADMIN_EMAIL, ADMIN_PASSWORD)
    const token = auth.accessToken
    const me = await api.me(token)
    const suffix = uniqueSuffix()

    const periods = await api.listBillingPeriods(token)
    const openPeriod = periods.find((period) => period.status === 'OPEN')
    expect(openPeriod).toBeTruthy()
    const periodId = openPeriod.id

    await loginAsAdmin(page)

    const cleanups = await settleBooks(api, token, me.organizationId, periodId, suffix)

    await test.step('run reconciliation in the browser and resolve its cases', async () => {
      await page.goto(`${E2E_BASE_URL}/reconciliation`)
      await expect(page.getByRole('heading', { name: '对账运行', level: 1 })).toBeVisible({ timeout: 20_000 })
      await page.getByRole('button', { name: '运行对账' }).click()
      await page.waitForURL(/\/reconciliation\/\d+$/, { timeout: 20_000 })
      // COMPLETED runs display the 已完成 status tag.
      await expect(page.getByText('已完成').first()).toBeVisible({ timeout: 120_000 })

      // The case table is a separate react-query fetch from the run status and
      // can lag behind it. Never decide "no cases" from a still-loading
      // skeleton: wait in every loop iteration for the cases query to settle
      // (the explicit empty state OR at least one row), then act on real data.
      for (let guard = 0; guard < 20; guard += 1) {
        await expect(
          page
            .getByText('当前没有符合筛选条件的差异案例')
            .or(page.locator('tr.ant-table-row').first()),
        ).toBeVisible({ timeout: 30_000 })
        // CLOSE readiness blocks on ANY unresolved case (OPEN or INVESTIGATING).
        // Earlier runs only handled 待处理 and missed 调查中 leftovers.
        const unresolvedRow = page
          .locator('tr.ant-table-row')
          .filter({ hasText: /待处理|调查中/ })
          .first()
        if ((await unresolvedRow.count()) === 0) {
          break
        }
        await unresolvedRow.getByRole('button', { name: /详\s*情/ }).click()
        await page.waitForURL(/\/reconciliation\/cases\/\d+$/, { timeout: 20_000 })
        // Detail page can be OPEN (show 开始调查) or already INVESTIGATING (show 标记已解决).
        // Wait for the hydrating detail to expose either action, then follow the right path.
        await expect(
          page.getByRole('button', { name: '开始调查' }).or(page.getByRole('button', { name: '标记已解决' })),
        ).toBeVisible({ timeout: 20_000 })
        const startButton = page.getByRole('button', { name: '开始调查' })
        if ((await startButton.count()) > 0) {
          await startButton.click()
          await expect(page.getByRole('button', { name: '标记已解决' })).toBeVisible({ timeout: 20_000 })
        }
        await page.getByRole('button', { name: '标记已解决' }).click()
        const modal = page.locator('.ant-modal:visible').last()
        await modal.getByPlaceholder('请输入处理原因').fill('OPERATIONAL_DECISION')
        await modal.getByPlaceholder('请说明本次案例的处理结论').fill('E2E reconciliation close drill')
        await modal.getByRole('button', { name: '确认解决' }).click()
        await expect(page.getByText('已解决').first()).toBeVisible({ timeout: 20_000 })
        await page.getByRole('button', { name: '← 返回运行详情' }).click()
        await expect(page.getByRole('heading', { name: '对账运行详情', level: 1 })).toBeVisible({ timeout: 20_000 })
        // The run-detail case list is cached by react-query and would keep
        // showing the stale 调查中 tag; reload to observe the resolved state.
        await page.reload()
        await expect(page.getByRole('heading', { name: '对账运行详情', level: 1 })).toBeVisible({ timeout: 20_000 })
      }
      // Guard the final assertion with the same settled-data wait: a
      // still-loading table is empty and must not be mistaken for "no cases".
      await expect(
        page
          .getByText('当前没有符合筛选条件的差异案例')
          .or(page.locator('tr.ant-table-row').first()),
      ).toBeVisible({ timeout: 30_000 })
      await expect(page.locator('tr.ant-table-row').filter({ hasText: /待处理|调查中/ })).toHaveCount(0, { timeout: 20_000 })

      // Browser loop may miss a paginated or late-arriving case; use the API
      // as a deterministic fallback to close any remaining unresolved cases
      // from the run we just created (does not bypass browser reconciliation).
      const runUrl = new URL(page.url())
      const runId = Number(runUrl.pathname.split('/').pop())
      if (runId) {
        const remaining = await api.listReconciliationCases(token, { runId })
        for (const item of remaining.items) {
          if (item.status === 'RESOLVED') continue
          if (item.status === 'OPEN') {
            await api.investigateCase(token, item.id)
          }
          await api.resolveCase(token, item.id, {
            reasonCode: 'OPERATIONAL_DECISION',
            resolutionNote: 'E2E reconciliation close drill (API fallback)',
          })
        }
      }
    })

    await test.step('close the period in the browser', async () => {
      const finalReadiness = await api.closeReadiness(token, periodId)
      const blockers = finalReadiness.checks
        .filter((check) => check.result !== 'PASS')
        .map((check) => ({
          blockerCode: check.blockerCode,
          result: check.result,
          itemCount: check.itemCount,
          summary: check.summary,
        }))
      expect(
        finalReadiness.ready,
        `period close readiness is blocked: ${JSON.stringify(blockers)}`,
      ).toBe(true)

      await page.goto(`${E2E_BASE_URL}/period-close/${periodId}`)
      await expect(page.getByRole('heading', { name: '账期关闭准备度', level: 1 })).toBeVisible({ timeout: 20_000 })
      // The close action is only usable when readiness is green.
      await expect(page.getByText('可以关闭').first()).toBeVisible({ timeout: 20_000 })
      await page.getByRole('button', { name: '关闭账期' }).click()
      const modal = page.locator('.ant-modal:visible').last()
      await modal.getByRole('button', { name: '确认关闭' }).click()
      // A closed period exposes the reopen action and hides the close action.
      await expect(page.getByRole('button', { name: '重新开放账期' })).toBeVisible({ timeout: 60_000 })
      await expect(page.getByRole('button', { name: '关闭账期' })).toHaveCount(0)
    })

    await test.step('a financial write in the closed period is rejected in the UI', async () => {
      await page.goto(`${E2E_BASE_URL}/budgets`)
      await page.getByRole('button', { name: '创建预算' }).click()
      await pickAntdSelect(page, '账期', 'CLOSED')
      await pickAntdSelect(page, '范围类型', '组织')
      const modal = page.locator('.ant-modal:visible').last()
      await modal.getByLabel('范围 ID').fill(String(me.organizationId))
      await modal.getByLabel('总额').fill('500.00000000')
      await modal.getByRole('button', { name: /创\s*建/ }).click()
      await expect(modal.getByText('账期未开放').first()).toBeVisible({ timeout: 20_000 })
      await modal.getByRole('button', { name: /取\s*消/ }).click()
    })

    await test.step('reopen the period in the browser', async () => {
      await page.goto(`${E2E_BASE_URL}/period-close/${periodId}`)
      await page.getByRole('button', { name: '重新开放账期' }).click()
      const modal = page.locator('.ant-modal:visible').last()
      await modal.getByPlaceholder('请输入原因').fill('E2E_REOPEN_DRILL')
      await modal.getByPlaceholder('请说明重新开放的业务原因').fill('E2E period reopen drill')
      await modal.getByRole('button', { name: '确认重新开放' }).click()
      // Back to OPEN: the close action is offered again and reopen is hidden.
      await expect(page.getByRole('button', { name: '关闭账期' })).toBeVisible({ timeout: 60_000 })
      await expect(page.getByRole('button', { name: '重新开放账期' })).toHaveCount(0)
    })

    test.info().annotations.push({
      type: 'cleanups',
      description: cleanups.join(', '),
    })
  })
})

async function settleBooks(
  api: ApiClient,
  token: string,
  organizationId: number,
  periodId: number,
  suffix: string,
): Promise<string[]> {
  const notes: string[] = []
  const project = await api.createProject(token, { name: `E2E 结算项目 ${suffix}`, code: `settle-${suffix}` })
  const readiness = await api.closeReadiness(token, periodId)
  for (const check of readiness.checks) {
    if (check.result !== 'FAIL' || check.itemCount === 0) {
      continue
    }
    if (check.blockerCode === 'UNALLOCATED_CHARGES') {
      const samples: string[] = Array.isArray(check.summary?.sampleChargeFactIds)
        ? check.summary!.sampleChargeFactIds as string[]
        : []
      for (const id of samples) {
        const charge = await api.getCharge(token, Number(id))
        const decision = await api.createManualAllocation(token, { chargeId: charge.id }, [
          { allocatedAmount: capitalize8(charge.amount), currency: charge.currency, projectId: project.id },
        ])
        await api.confirmAllocation(token, decision.id)
      }
      notes.push(`allocated ${samples.length} leftover charge(s)`)
    } else if (check.blockerCode === 'UNPOSTED_APPROVED_EXPENSES') {
      const reviews = await api.listExpenseReviews(token, 'APPROVED')
      for (const expense of reviews.items) {
        await postExpenseIfNeeded(api, token, expense, project.id)
      }
      notes.push(`posted ${reviews.items.length} leftover approved expense(s)`)
    } else {
      notes.push(`${check.blockerCode} blocked with ${check.itemCount} item(s)`)
    }
  }
  if (notes.length === 0) {
    notes.push(`close readiness already green for org ${organizationId}`)
  }
  return notes
}

async function postExpenseIfNeeded(
  api: ApiClient,
  token: string,
  expense: Expense & { postingReady: boolean },
  projectId: number,
): Promise<void> {
  if (!expense.postingReady) {
    return
  }
  try {
    const decision = await api.createManualAllocation(token, { expenseId: expense.id }, [
      { allocatedAmount: capitalize8(expense.amount), currency: expense.currency, projectId },
    ])
    await api.confirmAllocation(token, decision.id)
  } catch {
    // A confirmed allocation may already exist; the posting below is the step.
  }
  await api.postExpense(token, expense.id)
}

function capitalize8(amount: string): string {
  if (/^\d+\.\d{8}$/.test(amount)) {
    return amount
  }
  return Number(amount).toFixed(8)
}