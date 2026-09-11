import { expect, test } from '@playwright/test'
import { loginAsAdmin } from './support/auth'
import { E2E_BASE_URL } from './support/env'

/** M19 C2: intelligence workspace pages render with briefing headings. */
const cases = [
  { path: '/intelligence/anomalies', heading: '异常' },
  { path: '/intelligence/forecasts', heading: '预测' },
  { path: '/intelligence/savings', heading: '节约建议' },
] as const

for (const { path, heading } of cases) {
  test(`workspace page renders: ${heading}`, async ({ page }) => {
    await loginAsAdmin(page)
    await page.goto(`${E2E_BASE_URL}${path}`)
    await expect(page.getByRole('heading', { name: heading, level: 1 })).toBeVisible({ timeout: 20_000 })
  })
}
