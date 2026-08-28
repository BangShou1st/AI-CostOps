import { expect, test } from '@playwright/test'
import { loginAs } from './support/auth'
import { E2E_BASE_URL, uniqueSuffix } from './support/env'

/**
 * Wrong-role negative path: a freshly registered account only ever receives
 * the EMPLOYEE role. Through the UI the employee must neither see finance-only
 * navigation nor gain any finance surface by typing its URL directly; the
 * permitted employee surface must keep working.
 */
test.describe('authorization negative (wrong role via UI)', () => {
  test('employee cannot reach finance-only surfaces', async ({ page }) => {
    const email = `e2e-employee-${uniqueSuffix()}@example.test`
    const password = 'e2e-synthetic-password'

    await test.step('register an employee via the public form', async () => {
      await page.goto(`${E2E_BASE_URL}/register`)
      await page.getByLabel('姓名').fill('E2E Employee')
      await page.getByLabel('邮箱').fill(email)
      await page.getByLabel('密码').fill(password)
      await page.getByRole('button', { name: '创建账号' }).click()
      await expect(page).toHaveURL(/\/login$/, { timeout: 20_000 })
    })

    await test.step('the employee lands on the employee-only surface', async () => {
      await loginAs(page, email, password)
      await expect(page.getByText('我的报销').first()).toBeVisible({ timeout: 20_000 })
    })

    await test.step('finance navigation is absent for the employee', async () => {
      for (const hidden of ['导入', '预算', '账本', '对账', '期间结账', '分摊规则']) {
        await expect(page.getByRole('menuitem', { name: hidden })).toHaveCount(0)
      }
      await expect(page.getByRole('menuitem', { name: '我的报销' })).toHaveCount(1)
    })

    await test.step('direct URLs to finance surfaces render 403, not the feature', async () => {
      for (const path of ['/imports', '/budgets', '/ledger', '/reconciliation', '/period-close', '/allocation-rules']) {
        await page.goto(`${E2E_BASE_URL}${path}`)
        await expect(page.getByRole('alert')).toBeVisible({ timeout: 20_000 })
        await expect(page.getByRole('heading', { name: '403', level: 1 })).toBeVisible()
      }
    })

    await test.step('the allowed employee surface still works after the negative checks', async () => {
      await page.goto(`${E2E_BASE_URL}/expenses`)
      await expect(page.getByText('我的报销').first()).toBeVisible({ timeout: 20_000 })
      await expect(page.getByRole('button', { name: '新建报销' })).toBeVisible()
    })
  })
})