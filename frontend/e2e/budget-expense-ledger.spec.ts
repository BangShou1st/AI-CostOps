import { expect, test } from '@playwright/test'
import { ApiClient } from './support/api'
import { loginAsAdmin } from './support/auth'
import { ADMIN_EMAIL, ADMIN_PASSWORD, E2E_BASE_URL, currentBusinessDates, uniqueSuffix } from './support/env'
import { addAllocationLine, pickAntdSelect } from './support/ui'

/**
 * Budget -> commitment state, Expense -> approval -> posting -> ledger
 * correction. Every user-visible transition is driven through the browser; the
 * API only creates the tenant-level prerequisites (project target and period
 * lookup) this spec does not set out to validate. The expense amount is read
 * back from the API only so the allocation line can exactly match it.
 */
test.describe('budget, expense and ledger lifecycle', () => {
  test('budget commitment, expense approval/posting and ledger correction via the browser', async ({ page, request }) => {
    const api = new ApiClient(request)
    const auth = await api.login(ADMIN_EMAIL, ADMIN_PASSWORD)
    const token = auth.accessToken
    const me = await api.me(token)
    const suffix = uniqueSuffix()
    const dates = currentBusinessDates()

    const periods = await api.listBillingPeriods(token)
    const openPeriod = periods.find((period) => period.status === 'OPEN')
    expect(openPeriod).toBeTruthy()
    const project = await api.createProject(token, {
      name: `E2E 项目 ${suffix}`,
      code: `e2e-${suffix}`,
    })
    const targets = await api.listAllocationTargets(token)
    const projectLabel = targets.find((entry) => entry.type === 'PROJECT' && entry.id === String(project.id))?.name
    expect(projectLabel).toBeTruthy()

    await loginAsAdmin(page)

    await test.step('create a budget for the open period and verify the not-over state', async () => {
      await page.goto(`${E2E_BASE_URL}/budgets`)
      await page.getByRole('button', { name: '创建预算' }).click()
      await pickAntdSelect(page, '账期', 'OPEN')
      await pickAntdSelect(page, '范围类型', '组织')
      const modal = page.locator('.ant-modal:visible').last()
      await modal.getByLabel('范围 ID').fill(String(me.organizationId))
      await modal.getByLabel('总额').fill('1000.00000000')
      await modal.getByRole('button', { name: /创\s*建/ }).click()
      await expect(page.getByText('未超支').first()).toBeVisible({ timeout: 20_000 })
    })

    await test.step('request a commitment and approve it to the active state', async () => {
      // The only budget in this isolated organization is the one just created.
      await page.locator('tr.ant-table-row').first().click()
      await expect(page.getByRole('heading', { name: /预算详情/, level: 1 })).toBeVisible({ timeout: 20_000 })
      await page.getByRole('button', { name: '申请承诺' }).click()
      const modal = page.locator('.ant-modal:visible').last()
      await modal.locator('input[aria-label="承诺金额"]').fill('100.00000000')
      await modal.getByRole('button', { name: '确认申请' }).click()
      await expect(page.getByText('待审批').first()).toBeVisible({ timeout: 20_000 })

      await page.locator('.ant-card').filter({ hasText: '承诺' }).locator('tr.ant-table-row').first().click()
      await expect(page.getByRole('heading', { name: /承诺详情/, level: 1 })).toBeVisible({ timeout: 20_000 })
      await page.getByRole('button', { name: /批\s*准/ }).click()
      await expect(page.getByText('已生效').first()).toBeVisible({ timeout: 20_000 })
    })

    await test.step('create an expense with evidence and submit it', async () => {
      await page.goto(`${E2E_BASE_URL}/expenses/new`)
      await page.getByPlaceholder('请选择日期').fill(dates.expenseDate)
      await page.getByPlaceholder('请选择日期').press('Enter')
      await page.getByRole('spinbutton').fill('12.34')
      await page.locator('input[maxlength="3"]').fill('CNY')
      await page.getByRole('button', { name: /创\s*建/ }).click()
      await page.waitForURL(/\/expenses\/\d+$/, { timeout: 20_000 })
      const expenseId = Number(page.url().split('/').pop())
      await expect(page.locator('.ant-card-head-title', { hasText: '报销 ' })).toBeVisible({ timeout: 20_000 })

      await page.getByRole('button', { name: '上传凭证' }).click()
      await page.locator('input[type="file"]').setInputFiles({
        name: 'synthetic-receipt.txt',
        mimeType: 'text/plain',
        buffer: Buffer.from('synthetic expense evidence; no real user data', 'utf8'),
      })
      await expect(page.getByText('已上传').first()).toBeVisible({ timeout: 20_000 })
      await page.getByRole('button', { name: /提\s*交/ }).click()
      await expect(page.getByText('已提交').first()).toBeVisible({ timeout: 20_000 })

      const expense = await api.getExpense(token, expenseId)
      const expenseAmount = expense.amount
      await page.goto(`${E2E_BASE_URL}/expense-reviews`)
      await expect(page.locator('.ant-card-head-title', { hasText: '费用审核' })).toBeVisible({ timeout: 20_000 })
      await page.getByRole('button', { name: /审\s*核/ }).first().click()
      await expect(page.locator('.ant-card-head-title', { hasText: '报销审核 ' })).toBeVisible({ timeout: 20_000 })

      await page.getByRole('button', { name: /批\s*准/, exact: true }).click()
      await expect(page.getByText('已批准').first()).toBeVisible({ timeout: 20_000 })

      // Manual allocation is required before posting; both are driven here.
      await page.getByRole('button', { name: '添加分摊行' }).click()
      await addAllocationLine(page, 1, expenseAmount, projectLabel!)
      await page.getByRole('button', { name: '创建分摊草稿' }).click()
      await expect(page.getByRole('button', { name: '确认分摊' })).toBeVisible({ timeout: 20_000 })
      await page.getByRole('button', { name: '确认分摊' }).click()
      // The confirm action stays rendered but becomes disabled once the
      // decision is CONFIRMED; assert the persisted state instead of the DOM.
      await expect
        .poll(
          async () => {
            const decisions = await api.listDecisionsForExpense(token, expenseId)
            return decisions.some((decision) => decision.status === 'CONFIRMED')
          },
          { timeout: 20_000 },
        )
        .toBe(true)

      await page.getByRole('button', { name: /记\s*账/ }).click()
      await expect(page.getByText('已记账').first()).toBeVisible({ timeout: 20_000 })
    })

    await test.step('the posted expense appears in the ledger and can be corrected', async () => {
      await page.goto(`${E2E_BASE_URL}/ledger`)
      await expect(page.getByRole('heading', { name: '账本', level: 1 })).toBeVisible({ timeout: 20_000 })
      const postingRow = page.locator('tr.ant-table-row').filter({ hasText: '报销' }).first()
      await expect(postingRow).toBeVisible({ timeout: 20_000 })
      // The ledger list navigates through the 来源 ID cell link, not a row click.
      await postingRow.locator('a').first().click()
      await expect(page.getByRole('heading', { name: /账本发布 #\d+/, level: 1 })).toBeVisible({ timeout: 20_000 })
      await page.locator('tr.ant-table-row').first().getByRole('link', { name: '查看' }).click()
      await expect(page.getByRole('heading', { name: /分录血缘/, level: 1 })).toBeVisible({ timeout: 20_000 })

      await page.getByRole('button', { name: /纠\s*正/ }).click()
      await pickAntdSelect(page, '纠正账期', String(openPeriod.id))
      const modal = page.locator('.ant-modal:visible').last()
      await modal.getByLabel('原因说明').fill('E2E correction drill')
      await modal.getByRole('button', { name: '提交纠正' }).click()
      await expect(page.getByRole('button', { name: /纠\s*正/ })).toHaveCount(0, { timeout: 20_000 })
    })
  })
})