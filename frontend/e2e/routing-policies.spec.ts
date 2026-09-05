import { expect, test } from '@playwright/test'
import { ApiClient } from './support/api'
import { loginAsAdmin } from './support/auth'
import { ADMIN_EMAIL, ADMIN_PASSWORD, API_ROOT, E2E_BASE_URL } from './support/env'

type RoutingPolicy = {
  id: number
  modelId: number
  projectId: number | null
  version: number
  status: 'DRAFT' | 'ACTIVE' | 'RETIRED'
  candidates: Array<{
    providerAccountId: number
    providerModelId: number
    priority: number
    status: 'ACTIVE' | 'DISABLED'
    privacyRegionCode: string | null
  }>
}

/** Browser/API acceptance of immutable revision and activation semantics. */
test('routing policy can be revised and activated without exposing secrets', async ({ page, request }) => {
  const api = new ApiClient(request)
  const auth = await api.login(ADMIN_EMAIL, ADMIN_PASSWORD)
  const response = await request.get(`${API_ROOT}/routing-policies?page=0&size=100`, {
    headers: { Authorization: `Bearer ${auth.accessToken}` },
  })
  expect(response.ok()).toBeTruthy()
  const pageBody = await response.json() as { items: RoutingPolicy[] }
  const active = pageBody.items.find((policy) => policy.status === 'ACTIVE')
  expect(active, 'dev bootstrap must provide an ACTIVE routing policy').toBeTruthy()

  const revisionResponse = await request.post(`${API_ROOT}/routing-policies/${active!.id}/revisions`, {
    headers: { Authorization: `Bearer ${auth.accessToken}` },
  })
  expect(revisionResponse.status()).toBe(201)
  const draft = await revisionResponse.json() as RoutingPolicy
  expect(draft.status).toBe('DRAFT')
  expect(draft.version).toBe(active!.version + 1)

  const updateResponse = await request.put(`${API_ROOT}/routing-policies/${draft.id}`, {
    headers: { Authorization: `Bearer ${auth.accessToken}` },
    data: { candidates: draft.candidates },
  })
  expect(updateResponse.ok()).toBeTruthy()
  const activateResponse = await request.post(`${API_ROOT}/routing-policies/${draft.id}/activate`, {
    headers: { Authorization: `Bearer ${auth.accessToken}` },
  })
  expect(activateResponse.ok()).toBeTruthy()
  const activated = await activateResponse.json() as RoutingPolicy
  expect(activated.status).toBe('ACTIVE')

  await loginAsAdmin(page)
  await page.goto(`${E2E_BASE_URL}/settings/routing-policies`)
  await expect(page.getByRole('heading', { name: '路由策略', level: 1 })).toBeVisible({ timeout: 20_000 })
  await expect(page.getByText('已启用').first()).toBeVisible({ timeout: 20_000 })
  await expect(page.locator('body')).not.toContainText('sk-')
})
