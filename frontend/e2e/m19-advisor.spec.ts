import { expect, test } from '@playwright/test'
import { loginAsAdmin } from './support/auth'
import { E2E_BASE_URL } from './support/env'

/** M19 C3: governed advisor page renders the explanation boundary. */
test('advisor page renders without chat UI', async ({ page }) => {
  await loginAsAdmin(page)
  await page.goto(`${E2E_BASE_URL}/advisor`)
  await expect(page.getByRole('heading', { name: 'AI Advisor', level: 1 })).toBeVisible({ timeout: 20_000 })
  await expect(page.getByText('不计算金额')).toBeVisible({ timeout: 20_000 })
})
