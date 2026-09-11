import { expect, test } from '@playwright/test'
import { loginAsAdmin } from './support/auth'
import { E2E_BASE_URL } from './support/env'

/** M19 C4: provider hub pages render with governance framing. */
const cases = [
  { path: '/settings/providers', heading: 'Provider Gallery' },
  { path: '/settings/provider-connections', heading: '连接配置' },
  { path: '/settings/provider-models', heading: '模型目录' },
] as const

for (const { path, heading } of cases) {
  test(`provider hub renders: ${heading}`, async ({ page }) => {
    await loginAsAdmin(page)
    await page.goto(`${E2E_BASE_URL}${path}`)
    await expect(page.getByRole('heading', { name: heading, level: 1 })).toBeVisible({ timeout: 20_000 })
  })
}

test('connection secrets stay masked', async ({ page }) => {
  await loginAsAdmin(page)
  await page.goto(`${E2E_BASE_URL}/settings/provider-connections`)
  await expect(page.getByRole('heading', { name: '连接配置', level: 1 })).toBeVisible({ timeout: 20_000 })
  await expect(page.locator('body')).not.toContainText('sk-')
})
