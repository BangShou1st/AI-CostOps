import { expect, test } from '@playwright/test'
import { expectSessionRestored, loginAsAdmin } from './support/auth'
import { E2E_BASE_URL } from './support/env'

test.describe('auth and session (user visible)', () => {
  test('login, refresh-restore, logout and unauthorized redirect', async ({ page, context }) => {
    await test.step('login through the real form', async () => {
      await page.goto(`${E2E_BASE_URL}/login`)
      await page.getByLabel('邮箱').fill(process.env.AICOSTOPS_E2E_ADMIN_EMAIL ?? 'admin@example.test')
      await page.getByLabel('密码').fill(process.env.AICOSTOPS_E2E_ADMIN_PASSWORD ?? 'change-me-local-only')
      await page.getByRole('button', { name: '登录' }).click()
      await expect(page.getByRole('button', { name: '退出登录' })).toBeVisible({ timeout: 20_000 })
      await page.goto(`${E2E_BASE_URL}/workbench`)
      await expect(page.getByRole('heading', { name: '工作台', level: 1 })).toBeVisible({ timeout: 20_000 })
    })

    await test.step('a full reload restores the session from the refresh cookie', async () => {
      await expectSessionRestored(page)
      // The restored session must carry real authorization: navigate to a
      // finance surface and see it render, not bounce to /login.
      await page.goto(`${E2E_BASE_URL}/imports`)
      await expect(page.getByRole('heading', { name: '导入', level: 1 })).toBeVisible({ timeout: 20_000 })
    })

    await test.step('logout returns to the login page', async () => {
      await page.goto(`${E2E_BASE_URL}/workbench`)
      await page.getByRole('button', { name: '退出登录' }).click()
      await expect(page).toHaveURL(/\/login$/, { timeout: 20_000 })
    })

    await test.step('anonymous access to a protected route redirects to login', async () => {
      await page.goto(`${E2E_BASE_URL}/workbench`)
      await expect(page).toHaveURL(/\/login$/, { timeout: 20_000 })
    })

    await test.step('an expired refresh cookie cannot bootstrap a protected session', async () => {
      // Re-authenticate, then remove every cookie (the refresh token lives in
      // an HttpOnly cookie). Reloading must therefore loose the session and
      // send the user back to the login page instead of rendering finance.
      await loginAsAdmin(page)
      await expect(page.getByRole('button', { name: '退出登录' })).toBeVisible({ timeout: 20_000 })
      await context.clearCookies()
      await page.reload()
      await expect(page).toHaveURL(/\/login$/, { timeout: 20_000 })
      await expect(page.getByLabel('邮箱')).toBeVisible()
    })
  })
})