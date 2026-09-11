import { expect, test } from '@playwright/test'
import { loginAsAdmin } from './support/auth'
import { E2E_BASE_URL } from './support/env'

/** M19 C1: V3 shell exposes the Cost Intelligence overview with a briefing spine. */
test('cost intelligence overview renders briefing spine or governed empty state', async ({ page }) => {
  await loginAsAdmin(page)
  await page.goto(`${E2E_BASE_URL}/intelligence/overview`)
  await expect(page.getByRole('heading', { name: '成本智能总览', level: 1 })).toBeVisible({ timeout: 20_000 })

  const emptyState = page.getByText('暂无智能分析结果')
  const answerStrip = page.getByText('Are we okay?')
  await expect(emptyState.or(answerStrip)).toBeVisible({ timeout: 20_000 })

  if (await answerStrip.isVisible()) {
    await expect(page.getByText('What changed?')).toBeVisible()
    await expect(page.getByText('What will happen?')).toBeVisible()
    await expect(page.getByText('What can I do?')).toBeVisible()
  }

  await expect(page.getByRole('link', { name: '智能总览' })).toBeVisible()
})
