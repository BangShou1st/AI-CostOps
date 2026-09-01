import { expect, test } from '@playwright/test'
import { ApiClient, deepSeekFixtureZip } from './support/api'
import { loginAsAdmin } from './support/auth'
import { ADMIN_EMAIL, ADMIN_PASSWORD, E2E_BASE_URL, currentBusinessDates, uniqueSuffix } from './support/env'
import { addAllocationLine, pickAntdSelectIn } from './support/ui'

/**
 * Import -> review/confirm -> allocation, driven through the browser. The
 * provider account and the allocation target are API prerequisites because
 * they are not what this test validates; the upload, the confirmation and the
 * allocation decision are all done by a real user in the UI.
 */
test.describe('provider import and allocation', () => {
  test('upload, review/confirm, and allocate a synthetic DeepSeek import via the browser', async ({ page, request }) => {
    const api = new ApiClient(request)
    const auth = await api.login(ADMIN_EMAIL, ADMIN_PASSWORD)
    const token = auth.accessToken
    const suffix = uniqueSuffix()
    const dates = currentBusinessDates()

    const provider = await api.createProviderAccount(token, {
      providerCode: 'DEEPSEEK',
      displayName: `E2E DeepSeek ${suffix}`,
      externalAccountRef: `synthetic-e2e-${suffix}`,
    })
    const project = await api.createProject(token, {
      name: `E2E 项目 ${suffix}`,
      code: `e2e-${suffix}`,
    })

    await loginAsAdmin(page)

    await test.step('upload the synthetic provider bill through the browser', async () => {
      await page.goto(`${E2E_BASE_URL}/imports`)
      await page.getByRole('button', { name: '上传供应商账单' }).click()
      const modal = page.locator('.ant-modal:visible').last()
      await expect(modal).toBeVisible({ timeout: 15_000 })

      const zip = deepSeekFixtureZip(dates.month, dates.startIso, dates.endIso, suffix)
      await pickAntdSelectIn(modal, page, '供应商账号', provider.displayName)
      await pickAntdSelectIn(modal, page, '来源类型', '文件导出')
      await modal.locator('input[type="file"]').setInputFiles({
        name: 'synthetic-deepseek.zip',
        mimeType: 'application/zip',
        buffer: zip,
      })
      await modal.getByRole('button', { name: /上\s*传/ }).click()
      // The import detail page follows; the worker must parse the archive and
      // reach READY_FOR_REVIEW before the confirm action is offered.
      await expect(page.getByRole('button', { name: '确认导入' })).toBeVisible({ timeout: 120_000 })
    })

    await test.step('confirm the reviewed import', async () => {
      await page.getByRole('button', { name: '确认导入' }).click()
      await expect(page.getByText('已确认').first()).toBeVisible({ timeout: 30_000 })
      await expect(page.getByRole('button', { name: '确认导入' })).toHaveCount(0)
    })

    await test.step('allocate every canonical DeepSeek charge through the costs page', async () => {
      const targets = await api.listAllocationTargets(token)
      const targetLabel = targets.find(
        (entry) => entry.type === 'PROJECT' && entry.id === String(project.id))?.name
      expect(targetLabel).toBeTruthy()

      const charges = (await api.listCharges(token)).items.filter(
        (charge) => charge.providerCode === 'DEEPSEEK')
      expect(charges.length).toBeGreaterThan(0)

      for (const charge of charges) {
        await page.goto(`${E2E_BASE_URL}/costs/${charge.id}`)
        await expect(page.getByRole('heading', { name: /成本详情/, level: 1 })).toBeVisible()

        await page.getByRole('button', { name: '添加分摊行' }).click()
        await addAllocationLine(page, 1, normalizeAmount(charge.amount), targetLabel!)
        await page.getByRole('button', { name: '创建分摊草稿' }).click()
        await expect(page.getByRole('button', { name: '确认分摊' })).toBeVisible({ timeout: 20_000 })
        await page.getByRole('button', { name: '确认分摊' }).click()
        // The confirm action stays rendered but becomes disabled once the
        // decision is CONFIRMED; assert the persisted state instead of the DOM.
        await expect
          .poll(
            async () => {
              const decisions = await api.listDecisionsForCharge(token, charge.id)
              return decisions.some((decision) => decision.status === 'CONFIRMED')
            },
            { timeout: 20_000 },
          )
          .toBe(true)
      }
    })
  })
})

function normalizeAmount(amount: string): string {
  // The API money fields are scale-8 strings; normalizing a bare decimal like
  // "1.25" as well keeps the allocation line editor happy either way.
  if (/^\d+\.\d{8}$/.test(amount)) {
    return amount
  }
  return Number(amount).toFixed(8)
}