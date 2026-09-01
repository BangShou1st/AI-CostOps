import { expect, type Page } from '@playwright/test'

/**
 * Pick an antd Select option inside one modal container. antd v6 renders the
 * modal root as .ant-modal (role=dialog); the dropdown is matched inside the
 * visible dropdowns so options of page-level selects never leak in.
 */
export async function pickAntdSelect(
  page: Page,
  anchor: string,
  optionText: string | RegExp,
): Promise<void> {
  await pickAntdSelectIn(page.locator('.ant-modal:visible').last(), page, anchor, optionText)
}

export async function pickAntdSelectIn(
  container: ReturnType<Page['locator']>,
  page: Page,
  anchor: string,
  optionText: string | RegExp,
): Promise<void> {
  const field = container
    .locator('label, .ant-form-item')
    .filter({ hasText: anchor })
    .first()
  await field.locator('.ant-select').click()
  const dropdown = page.locator('.ant-select-dropdown:visible').last()
  await dropdown
    .locator('.ant-select-item-option')
    .filter({ hasText: optionText })
    .first()
    .click()
}

/** Fill one row of the allocation editor and pick its target option. */
export async function addAllocationLine(
  page: Page,
  rowIndex: number,
  amount: string,
  targetOptionText: string,
): Promise<void> {
  await page.locator(`input[aria-label="第 ${rowIndex} 行金额"]`).fill(amount)
  await page
    .locator(`select[aria-label="第 ${rowIndex} 行分摊对象"]`)
    .selectOption({ label: targetOptionText })
}

/** Wait until a status tag text appears anywhere on the page (bounded polling). */
export async function expectStatus(page: Page, text: string, timeout = 60_000): Promise<void> {
  await expect(page.getByText(text).first()).toBeVisible({ timeout })
}

/** Close an antd modal by pressing the OK button with the given text. */
export async function confirmModal(page: Page, okText: string): Promise<void> {
  const modal = page.locator('.ant-modal:visible').last()
  await modal.getByRole('button', { name: okText }).click()
}