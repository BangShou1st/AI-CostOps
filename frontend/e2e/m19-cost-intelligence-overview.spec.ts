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
    // Playwright getByText defaults to substring matching: each briefing question
    // also appears in its detail-section eyebrow (e.g. "02 · What changed?").
    // Use exact text so the assertion targets the four-question answer strip.
    await expect(page.getByText('What changed?', { exact: true })).toBeVisible()
    await expect(page.getByText('What will happen?', { exact: true })).toBeVisible()
    await expect(page.getByText('What can I do?', { exact: true })).toBeVisible()
  }

  // Sidebar uses Ant Design Menu semantics (role=menuitem, not link).
  await expect(page.getByRole('menuitem', { name: '智能总览' })).toBeVisible()
})
