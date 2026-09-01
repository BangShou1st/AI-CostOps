import { expect, type Page } from '@playwright/test'
import { ADMIN_EMAIL, ADMIN_PASSWORD, E2E_BASE_URL } from './env'

/**
 * Sign in through the real login page. All browser flows under test start here
 * so that authentication is validated as a user-visible behavior, not skipped.
 */
export async function loginAsAdmin(page: Page): Promise<void> {
  await loginAs(page, ADMIN_EMAIL, ADMIN_PASSWORD)
}

export async function loginAs(page: Page, email: string, password: string): Promise<void> {
  await page.goto(`${E2E_BASE_URL}/login`)
  await page.getByLabel('邮箱').fill(email)
  await page.getByLabel('密码').fill(password)
  await page.getByRole('button', { name: '登录' }).click()
  // Successful login lands on the application landing, which routes finance
  // admins to the workbench. Wait for the authenticated layout instead of a
  // sleep: the sign-out button only exists once the session is restored.
  await expect(page.getByRole('button', { name: '退出登录' })).toBeVisible({ timeout: 20_000 })
}

/** Full page reload that must restore the session from the refresh cookie. */
export async function expectSessionRestored(page: Page): Promise<void> {
  await page.reload()
  await expect(page.getByRole('button', { name: '退出登录' })).toBeVisible({ timeout: 20_000 })
}