import { expect, test, type BrowserContext, type Page } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { ApiClient, deepSeekFixtureZip, type AuthUser } from './support/api'
import { ADMIN_EMAIL, ADMIN_PASSWORD, API_ROOT, E2E_BASE_URL, currentBusinessDates } from './support/env'
import {
  createPrivateModelFixture,
  createSecondOrganizationFixture,
} from './support/m22-database-fixture'

type LoginResponse = {
  accessToken: string
  user: { id: string; displayName: string }
}

type RegisteredIdentity = {
  userId: string
  organizationMemberId: string
  organizationId: string
}

type ApiVerb = 'GET' | 'POST' | 'PUT' | 'PATCH'
// Response bodies cross the generated API's untyped JSON boundary in this E2E.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
type ApiResult = { status: number; body: any }

type OrgData = {
  organizationId: string
  email: string
  token: string
  providerAccount: { id: string; displayName: string }
  costAccount: { id: string; displayName: string }
  connection: { id: string; status: string; version: number; baseUrl: string }
  credential: { id: string; safeLabel: string; status: string }
  rawSecret: string
  project: { id: string; code: string; name: string; status: string }
  budget: { id: string; billingPeriodId: string; totalAmount: string; version: number; status: string }
  model: { modelId: string; providerModelId: string; modelKey: string; wireName: string }
  pricing: { id: string; status: string; version: number }
  policy: { id: string; status: string; version: number; candidates: unknown[] }
  candidateDraft: { id: string; status: string; version: number; candidates: unknown[] }
  profile: { id: string; status: string; version: number; providerModelId: string; projectId: string }
  advisorJob: { id: string; status: string; attemptCount: number; subjectType: string; subjectId: string }
  charge: { id: string; amount: string; currency: string }
  posting: { id: string; status: string }
}

test.use({ trace: 'retain-on-failure', screenshot: 'only-on-failure', video: 'retain-on-failure' })

test('M22 两组织独立浏览器会话与真实 API 数据隔离', async ({ browser }) => {
  test.setTimeout(600_000)
  // No storageState is passed: each context owns a fresh cookie jar, page
  // storage, JavaScript heap, and React Query cache.
  const contextA = await browser.newContext()
  const contextB = await browser.newContext()
  const employeeContext = await browser.newContext()
  const pageA = await contextA.newPage()
  const pageB = await contextB.newPage()
  const employeePage = await employeeContext.newPage()
  const suffix = randomUUID().replaceAll('-', '').slice(0, 16)
  const statuses: Array<{ operation: string; method: string; route: string; expected: number | number[]; actual: number }> = []

  const assertApi = async (
    context: BrowserContext,
    token: string,
    operation: string,
    method: ApiVerb,
    path: string,
    expected: number,
    body?: unknown,
    soft = false,
  ): Promise<ApiResult> => {
    const headers: Record<string, string> = { Authorization: `Bearer ${token}` }
    if (method !== 'GET') headers['Idempotency-Key'] = randomUUID()
    const response = await context.request.fetch(`${API_ROOT}${path}`, {
      method,
      headers,
      ...(body === undefined ? {} : { data: body }),
    })
    const result: ApiResult = {
      status: response.status(),
      body: response.status() === 204 ? null : await response.json().catch(() => null),
    }
    statuses.push({ operation, method, route: path.replace(/\/\d+(?=\/|$)/g, '/:id'), expected, actual: result.status })
    if (soft) {
      expect.soft(result.status, `${operation}: ${method} ${path}`).toBe(expected)
    } else {
      expect(result.status, `${operation}: ${method} ${path}`).toBe(expected)
    }
    return result
  }

  const registerThroughUi = async (page: Page, email: string, password: string, name: string) => {
    await page.goto(`${E2E_BASE_URL}/register`)
    await page.getByLabel('姓名').fill(name)
    await page.getByLabel('邮箱').fill(email)
    await page.getByLabel('密码').fill(password)
    const responsePromise = page.waitForResponse((response) =>
      response.url().endsWith('/api/v1/auth/register') && response.request().method() === 'POST')
    await page.getByRole('button', { name: '创建账号' }).click()
    const response = await responsePromise
    statuses.push({ operation: 'public registration', method: 'POST', route: '/auth/register', expected: 200, actual: response.status() })
    expect(response.status(), 'synthetic registration uses the real public API').toBe(200)
    await expect(page).toHaveURL(/\/login$/)
    return await response.json() as RegisteredIdentity
  }

  const loginThroughUi = async (page: Page, email: string, password: string) => {
    await page.goto(`${E2E_BASE_URL}/login`)
    await page.getByLabel('邮箱').fill(email)
    await page.getByLabel('密码').fill(password)
    const responsePromise = page.waitForResponse((response) =>
      response.url().endsWith('/api/v1/auth/login') && response.request().method() === 'POST')
    await page.getByRole('button', { name: '登录' }).click()
    const response = await responsePromise
    statuses.push({ operation: 'browser login', method: 'POST', route: '/auth/login', expected: 200, actual: response.status() })
    expect(response.status(), 'login uses the real authentication endpoint').toBe(200)
    await expect(page.getByRole('button', { name: '退出登录' })).toBeVisible({ timeout: 20_000 })
    return await response.json() as LoginResponse
  }

  const me = async (context: BrowserContext, token: string) => {
    const response = await context.request.get(`${API_ROOT}/auth/me`, {
      headers: { Authorization: `Bearer ${token}` },
    })
    statuses.push({ operation: 'authenticated identity', method: 'GET', route: '/auth/me', expected: 200, actual: response.status() })
    expect(response.status(), 'authenticated identity endpoint').toBe(200)
    return await response.json() as AuthUser
  }

  const dataFor = async (
    context: BrowserContext,
    identity: AuthUser,
    token: string,
    label: 'A' | 'B',
  ): Promise<OrgData> => {
    const api = new ApiClient(context.request)
    const suffixForOrg = `${suffix}-${label.toLowerCase()}`
    const providerDisplayName = `M22 ${label} Provider ${suffix}`
    const costDisplayName = `M22 ${label} Synthetic Cost ${suffix}`
    const projectName = `M22 ${label} Project ${suffix}`
    const projectCode = `m22-${label.toLowerCase()}-${suffix}`

    const providerAccount = await api.createProviderAccount(token, {
      providerCode: 'OPENCODE_ZEN',
      displayName: providerDisplayName,
      externalAccountRef: `${suffixForOrg}-provider`,
    })
    const costAccount = await api.createProviderAccount(token, {
      providerCode: 'DEEPSEEK',
      displayName: costDisplayName,
      externalAccountRef: `${suffixForOrg}-cost`,
    })
    const project = await api.createProject(token, { name: projectName, code: projectCode })

    const connectionResult = await assertApi(context, token, `${label}: own Provider Connection create`,
      'POST', '/provider-connections', 201, {
        providerAccountId: Number(providerAccount.id),
        templateCode: 'OPENCODE_ZEN',
      })
    const connection = connectionResult.body as OrgData['connection']
    const rawSecret = `m22-synthetic-${label.toLowerCase()}-${suffix}`
    const safeLabel = `M22 ${label} synthetic credential ${suffix}`
    const credentialResult = await assertApi(context, token, `${label}: own Credential create`,
      'POST', `/provider-connections/${connection.id}/credentials`, 201, { rawSecret, safeLabel })
    const credential = credentialResult.body as OrgData['credential']
    expect(JSON.stringify(credential)).not.toContain(rawSecret)

    // The built-in endpoint undergoes only public-DNS preflight. No probe,
    // model refresh, Gateway dispatch, or paid Provider request is made.
    const activated = await assertApi(context, token, `${label}: own Provider Connection activate`,
      'POST', `/provider-connections/${connection.id}/activate`, 200)
    connection.status = activated.body.status
    connection.version = activated.body.version

    const model = createPrivateModelFixture({
      organizationId: identity.organizationId.toString(),
      providerAccountId: providerAccount.id.toString(),
      suffix: suffixForOrg,
    })
    const modelCatalog = await assertApi(context, token, `${label}: own private Model Catalog list`,
      'GET', '/model-catalog', 200)
    expect(modelCatalog.body.some((entry: { modelKey: string }) => entry.modelKey === model.modelKey)).toBeTruthy()
    const providerModels = await assertApi(context, token, `${label}: own private Provider Model list`,
      'GET', '/provider-models', 200)
    expect(providerModels.body.some((entry: { id: string }) => entry.id === model.providerModelId)).toBeTruthy()

    const effectiveFrom = new Date(Date.now() - 60_000).toISOString()
    const pricingResult = await assertApi(context, token, `${label}: own Pricing Version create`,
      'POST', '/pricing-versions', 201, {
        providerAccountId: Number(providerAccount.id),
        providerModelId: Number(model.providerModelId),
        currency: 'CNY',
        effectiveFrom,
        rates: [
          { dimensionCode: 'INPUT_TOKEN', unitQuantity: 1_000_000, unitPrice: 0.12 },
          { dimensionCode: 'OUTPUT_TOKEN', unitQuantity: 1_000_000, unitPrice: 0.24 },
        ],
      })
    const draftPricing = pricingResult.body as OrgData['pricing']
    const activePricing = await assertApi(context, token, `${label}: own Pricing Version activate`,
      'POST', `/pricing-versions/${draftPricing.id}/activate`, 200)
    const pricing = activePricing.body as OrgData['pricing']

    const periods = await api.listBillingPeriods(token)
    const period = periods.find((entry) => entry.status === 'OPEN')
    expect(period, `${label} has an open synthetic test period`).toBeTruthy()
    const budgetResult = await assertApi(context, token, `${label}: own Budget create`,
      'POST', '/budgets', 201, {
        billingPeriodId: String(period!.id),
        scopeType: 'PROJECT',
        scopeId: String(project.id),
        currency: 'CNY',
        totalAmount: '1000.00000000',
      })
    const budget = budgetResult.body as OrgData['budget']

    const policyResult = await assertApi(context, token, `${label}: own Routing Policy create`,
      'POST', '/routing-policies', 201, {
        projectId: Number(project.id),
        modelId: Number(model.modelId),
        candidates: [{
          providerAccountId: Number(providerAccount.id),
          providerModelId: Number(model.providerModelId),
          priority: 0,
          status: 'ACTIVE',
        }],
      })
    const draftPolicy = policyResult.body as OrgData['policy']
    const activePolicy = await assertApi(context, token, `${label}: own Routing Policy activate`,
      'POST', `/routing-policies/${draftPolicy.id}/activate`, 200)
    const policy = activePolicy.body as OrgData['policy']

    const candidateDraftResult = await assertApi(context, token, `${label}: own Routing Policy candidate draft create`,
      'POST', '/routing-policies', 201, {
        projectId: Number(project.id),
        modelId: Number(model.modelId),
        candidates: [{
          providerAccountId: Number(providerAccount.id),
          providerModelId: Number(model.providerModelId),
          priority: 0,
          status: 'ACTIVE',
        }],
      })
    const candidateDraft = candidateDraftResult.body as OrgData['candidateDraft']

    const profileResult = await assertApi(context, token, `${label}: own Advisor Profile create`,
      'PUT', '/ai-advisor/profile', 200, {
        providerModelId: Number(model.providerModelId),
        projectId: Number(project.id),
        financialScopeType: 'PROJECT',
        financialScopeId: Number(project.id),
        budgetEnforcementMode: 'OPTIONAL',
      })
    const profile = profileResult.body as OrgData['profile']

    // Compose has no Advisor worker, so this persists a real queued explanation
    // job and frozen evidence without dispatching a request to any Provider.
    const advisorJobResult = await assertApi(context, token, `${label}: own Advisor explanation Job create`,
      'POST', '/ai-advisor/explanations', 201, {
        subjectType: 'BUDGET_RISK',
        subjectId: Number(budget.id),
      })
    const advisorJob = advisorJobResult.body as OrgData['advisorJob']
    const ownAdvisorJob = await assertApi(context, token, `${label}: own Advisor explanation Job read`,
      'GET', `/ai-advisor/explanations/${advisorJob.id}`, 200)
    expect(ownAdvisorJob.body).toMatchObject({
      id: Number(advisorJob.id),
      subjectType: 'BUDGET_RISK',
      subjectId: Number(budget.id),
    })

    const chargesBefore = new Set((await api.listCharges(token)).items.map((charge) => String(charge.id)))
    const dates = currentBusinessDates()
    const imported = await api.uploadProviderImport(
      token,
      deepSeekFixtureZip(dates.month, dates.startIso, dates.endIso, suffixForOrg),
      Number(costAccount.id),
    )
    const importId = Number((imported as { importBatchId?: number }).importBatchId)
    expect(importId).toBeGreaterThan(0)
    await expect.poll(async () => (await api.getImport(token, importId)).status,
      { timeout: 120_000 }).toBe('READY_FOR_REVIEW')
    await api.confirmImport(token, importId)

    const createdCharges = (await api.listCharges(token)).items.filter(
      (charge) => !chargesBefore.has(String(charge.id)) && charge.providerCode === 'DEEPSEEK',
    )
    expect(createdCharges).toHaveLength(1)
    const charge = createdCharges[0]
    const allocation = await api.createManualAllocation(token, { chargeId: charge.id }, [{
      allocatedAmount: charge.amount,
      currency: charge.currency,
      projectId: Number(project.id),
    }])
    await api.confirmAllocation(token, allocation.id)
    await api.postCharge(token, charge.id)

    const ledger = await assertApi(context, token, `${label}: own Ledger posting visible`,
      'GET', `/ledger/postings?page=0&size=100&projectId=${project.id}`, 200)
    const postingRow = (ledger.body.items as Array<{ id: string; status: string }>).find(
      (entry) => entry.status === 'POSTED',
    )
    expect(postingRow, `${label} synthetic charge was posted to its Ledger`).toBeTruthy()

    return {
      organizationId: identity.organizationId.toString(),
      email: identity.email,
      token,
      providerAccount: { id: String(providerAccount.id), displayName: providerAccount.displayName },
      costAccount: { id: String(costAccount.id), displayName: costAccount.displayName },
      connection: {
        id: String(connection.id),
        status: String(connection.status),
        version: Number(connection.version),
        baseUrl: String(connection.baseUrl),
      },
      credential: { id: String(credential.id), safeLabel: credential.safeLabel, status: credential.status },
      rawSecret,
      project: { id: String(project.id), code: projectCode, name: projectName, status: 'ACTIVE' },
      budget: {
        id: String(budget.id),
        billingPeriodId: String(budget.billingPeriodId),
        totalAmount: budget.totalAmount,
        version: Number(budget.version),
        status: budget.status,
      },
      model,
      pricing: { id: String(pricing.id), status: pricing.status, version: Number(pricing.version) },
      policy: {
        id: String(policy.id), status: policy.status, version: Number(policy.version),
        candidates: policy.candidates,
      },
      candidateDraft: {
        id: String(candidateDraft.id), status: String(candidateDraft.status),
        version: Number(candidateDraft.version), candidates: candidateDraft.candidates,
      },
      profile: {
        id: String(profile.id), status: profile.status, version: Number(profile.version),
        providerModelId: String(profile.providerModelId), projectId: String(profile.projectId),
      },
      advisorJob: {
        id: String(advisorJob.id), status: String(advisorJob.status),
        attemptCount: Number(advisorJob.attemptCount),
        subjectType: String(advisorJob.subjectType), subjectId: String(advisorJob.subjectId),
      },
      charge: { id: String(charge.id), amount: charge.amount, currency: charge.currency },
      posting: { id: String(postingRow!.id), status: postingRow!.status },
    }
  }

  try {
    const loginA = await loginThroughUi(pageA, ADMIN_EMAIL, ADMIN_PASSWORD)
    const identityA = await me(contextA, loginA.accessToken)
    const orgAId = identityA.organizationId.toString()

    const bEmail = `m22-b-${suffix}@example.test`
    const bPassword = `M22-B-${randomUUID()}!`
    const registrationB = await registerThroughUi(pageB, bEmail, bPassword, `M22 Organization B ${suffix}`)
    expect(registrationB.organizationId).toBe(orgAId)
    const orgBId = createSecondOrganizationFixture({
      slug: `m22-org-b-${suffix}`,
      userId: registrationB.userId,
      memberId: registrationB.organizationMemberId,
      firstOrganizationId: registrationB.organizationId,
    })
    expect(orgBId).not.toBe(orgAId)

    const loginB = await loginThroughUi(pageB, bEmail, bPassword)
    const identityB = await me(contextB, loginB.accessToken)
    expect(identityB.email).toBe(bEmail)
    expect(identityB.organizationId.toString()).toBe(orgBId)
    expect(identityA.email).toBe(ADMIN_EMAIL.toLowerCase())
    expect(identityA.organizationId.toString()).toBe(orgAId)

    const cookieA = (await contextA.cookies(`${E2E_BASE_URL}/api/v1/auth`))
      .find((cookie) => cookie.name === 'aicostops_refresh')
    const cookieB = (await contextB.cookies(`${E2E_BASE_URL}/api/v1/auth`))
      .find((cookie) => cookie.name === 'aicostops_refresh')
    expect(cookieA?.value).toBeTruthy()
    expect(cookieB?.value).toBeTruthy()
    expect(cookieA!.value).not.toBe(cookieB!.value)
    expect(loginA.accessToken).not.toBe(loginB.accessToken)

    await pageA.evaluate(() => localStorage.setItem('m22-context-isolation-probe', 'context-a'))
    expect(await pageB.evaluate(() => localStorage.getItem('m22-context-isolation-probe'))).toBeNull()
    expect(await pageA.evaluate((token) => Object.values(localStorage).includes(token), loginA.accessToken)).toBe(false)
    expect(await pageB.evaluate((token) => Object.values(localStorage).includes(token), loginB.accessToken)).toBe(false)
    await pageA.evaluate(() => localStorage.removeItem('m22-context-isolation-probe'))

    const orgA = await dataFor(contextA, identityA, loginA.accessToken, 'A')
    const orgB = await dataFor(contextB, identityB, loginB.accessToken, 'B')
    expect(orgA.organizationId).not.toBe(orgB.organizationId)

    // A token presented from B's context remains A; it cannot become B because
    // of B's cookie jar or URL. It also cannot read B-owned data.
    const substitutedMe = await contextB.request.get(`${API_ROOT}/auth/me`, {
      headers: { Authorization: `Bearer ${loginA.accessToken}` },
    })
    statuses.push({ operation: 'A token remains A in B context', method: 'GET', route: '/auth/me', expected: 200, actual: substitutedMe.status() })
    expect(substitutedMe.status()).toBe(200)
    expect(((await substitutedMe.json()) as AuthUser).email).toBe(identityA.email)
    await assertApi(contextB, loginA.accessToken, 'A token in B context cannot read B Connection',
      'GET', `/provider-connections/${orgB.connection.id}`, 404)
    expect((await me(contextB, loginB.accessToken)).email).toBe(identityB.email)

    // Prove that lists and filters are organization-bound for both directions.
    for (const [owner, foreign] of [[orgA, orgB], [orgB, orgA]] as const) {
      const context = owner === orgA ? contextA : contextB
      const connections = await assertApi(context, owner.token, `${owner.email}: own connection list`,
        'GET', '/provider-connections?page=0&size=100', 200)
      expect(connections.body.items.some((entry: { id: string | number }) => String(entry.id) === foreign.connection.id)).toBe(false)
      const providerAccounts = await assertApi(context, owner.token, `${owner.email}: own provider account list`,
        'GET', '/provider-accounts?page=0&size=100', 200)
      expect(providerAccounts.body.items.some((entry: { id: string | number }) =>
        String(entry.id) === foreign.providerAccount.id)).toBe(false)
      const projects = await assertApi(context, owner.token, `${owner.email}: own project list`,
        'GET', '/projects?status=ACTIVE&page=0&size=100', 200)
      expect(projects.body.items.some((entry: { id: string | number }) => String(entry.id) === foreign.project.id)).toBe(false)
      const budgetsByForeignProject = await assertApi(context, owner.token, `${owner.email}: foreign project budget filter`,
        'GET', `/budgets?scopeType=PROJECT&scopeId=${foreign.project.id}&billingPeriodId=${foreign.budget.billingPeriodId}`, 200)
      expect(budgetsByForeignProject.body.items).toHaveLength(0)
      const charges = await assertApi(context, owner.token, `${owner.email}: own cost list`,
        'GET', '/costs/charges?page=0&size=100', 200)
      expect(charges.body.items.some((entry: { id: string | number }) => String(entry.id) === foreign.charge.id)).toBe(false)
      const ledger = await assertApi(context, owner.token, `${owner.email}: foreign project Ledger filter`,
        'GET', `/ledger/postings?page=0&size=100&projectId=${foreign.project.id}`, 200)
      expect(ledger.body.items).toHaveLength(0)

      const modelCatalog = await assertApi(context, owner.token, `${owner.email}: foreign private Model Catalog hidden`,
        'GET', '/model-catalog', 200)
      expect.soft(modelCatalog.body.some((entry: { modelKey: string }) => entry.modelKey === foreign.model.modelKey),
        'private model catalog rows must be limited to global and current-organization rows').toBe(false)
      const providerModels = await assertApi(context, owner.token, `${owner.email}: foreign private Provider Model hidden`,
        'GET', '/provider-models', 200)
      expect.soft(providerModels.body.some((entry: { id: string | number; providerModelName?: string }) =>
        String(entry.id) === foreign.model.providerModelId || entry.providerModelName === foreign.model.wireName),
      'private provider models must be limited to global and current-organization rows').toBe(false)
      const pricingVersions = await assertApi(context, owner.token, `${owner.email}: own Pricing Version list`,
        'GET', '/pricing-versions', 200)
      expect(pricingVersions.body.some((entry: { id: string | number }) =>
        String(entry.id) === foreign.pricing.id)).toBe(false)
      const routingPolicies = await assertApi(context, owner.token, `${owner.email}: own Routing Policy list`,
        'GET', '/routing-policies?page=0&size=100', 200)
      expect(routingPolicies.body.items.some((entry: { id: string | number }) =>
        String(entry.id) === foreign.policy.id || String(entry.id) === foreign.candidateDraft.id)).toBe(false)
      const options = await assertApi(context, owner.token, `${owner.email}: foreign private routing options hidden`,
        'GET', `/routing-options?modelId=${foreign.model.modelId}`, 404, undefined, true)
      expect.soft(options.body?.code, 'routing options hide whether a foreign private model exists')
        .toBe('RESOURCE_NOT_FOUND')
    }

    // Exact resource reads return the product's privacy 404. Sensitive provider
    // credentials are safe metadata only, never raw secrets.
    for (const [attacker, victim] of [[orgA, orgB], [orgB, orgA]] as const) {
      const context = attacker === orgA ? contextA : contextB
      await assertApi(context, attacker.token, `${attacker.email}: foreign Connection detail`,
        'GET', `/provider-connections/${victim.connection.id}`, 404)
      await assertApi(context, attacker.token, `${attacker.email}: foreign Credential metadata`,
        'GET', `/provider-connections/${victim.connection.id}/credentials`, 404)
      await assertApi(context, attacker.token, `${attacker.email}: foreign Pricing Version list is scoped`,
        'GET', '/pricing-versions', 200)
      await assertApi(context, attacker.token, `${attacker.email}: foreign Routing Policy detail`,
        'GET', `/routing-policies/${victim.policy.id}`, 404)
      await assertApi(context, attacker.token, `${attacker.email}: foreign Budget detail`,
        'GET', `/budgets/${victim.budget.id}`, 404)
      await assertApi(context, attacker.token, `${attacker.email}: foreign Cost detail`,
        'GET', `/costs/charges/${victim.charge.id}`, 404)
      await assertApi(context, attacker.token, `${attacker.email}: foreign Ledger posting detail`,
        'GET', `/ledger/postings/${victim.posting.id}`, 404)
      await assertApi(context, attacker.token, `${attacker.email}: foreign Advisor Job and explanation detail`,
        'GET', `/ai-advisor/explanations/${victim.advisorJob.id}`, 404)
    }

    // Every attempted cross-organization write is followed by a read from the
    // true owner. The victim's ID and optimistic version are real and dynamic.
    for (const [attacker, victim] of [[orgA, orgB], [orgB, orgA]] as const) {
      const attackerContext = attacker === orgA ? contextA : contextB
      const ownerContext = victim === orgA ? contextA : contextB

      const forgedAccountName = `M22 forged orgId ${suffix} ${attacker === orgA ? 'a' : 'b'}`
      const forgedAccountResponse = await attackerContext.request.post(`${API_ROOT}/provider-accounts`, {
        headers: { Authorization: `Bearer ${attacker.token}` },
        data: {
          providerCode: 'OPENCODE_ZEN',
          displayName: forgedAccountName,
          externalAccountRef: `m22-forged-${suffix}-${attacker === orgA ? 'a' : 'b'}`,
          orgId: Number(victim.organizationId),
        },
      })
      const forgedAccountStatus = forgedAccountResponse.status()
      statuses.push({
        operation: 'forged orgId cannot create a foreign Provider Account',
        method: 'POST',
        route: '/provider-accounts',
        expected: [201, 400],
        actual: forgedAccountStatus,
      })
      expect([201, 400], 'the unsupported orgId is ignored or rejected').toContain(forgedAccountStatus)
      const forgedAccountId = forgedAccountStatus === 201
        ? String(((await forgedAccountResponse.json()) as { id: string | number }).id)
        : null
      const attackerAccountList = await assertApi(attackerContext, attacker.token,
        'caller organization verifies orgId create result', 'GET', '/provider-accounts?page=0&size=100', 200)
      expect(attackerAccountList.body.items.some((entry: { id: string | number; displayName: string }) =>
        entry.displayName === forgedAccountName && (forgedAccountId === null || String(entry.id) === forgedAccountId)))
        .toBe(forgedAccountStatus === 201)
      const victimAccountList = await assertApi(ownerContext, victim.token,
        'target organization verifies forged orgId did not create a Provider Account',
        'GET', '/provider-accounts?page=0&size=100', 200)
      expect(victimAccountList.body.items.some((entry: { displayName: string }) =>
        entry.displayName === forgedAccountName)).toBe(false)

      await assertApi(attackerContext, attacker.token, `${attacker.email}: foreign Provider Account update rejected`,
        'PATCH', `/provider-accounts/${victim.providerAccount.id}`, 404, {
          displayName: `M22 unauthorized ${suffix}`,
        })
      const ownerAccounts = await assertApi(ownerContext, victim.token, `${victim.email}: Provider Account unchanged after rejection`,
        'GET', '/provider-accounts?page=0&size=100', 200)
      expect(ownerAccounts.body.items.some((entry: { id: string | number; displayName: string }) =>
        String(entry.id) === victim.providerAccount.id && entry.displayName === victim.providerAccount.displayName)).toBe(true)

      const connectionBefore = (await assertApi(ownerContext, victim.token, `${victim.email}: Connection state before hostile writes`,
        'GET', `/provider-connections/${victim.connection.id}`, 200)).body
      await assertApi(attackerContext, attacker.token, `${attacker.email}: foreign Provider Connection update rejected`,
        'PUT', `/provider-connections/${victim.connection.id}`, 404, { baseUrl: 'https://example.org/v1' })
      const connectionAfterUpdate = (await assertApi(ownerContext, victim.token, `${victim.email}: Connection unchanged after update denial`,
        'GET', `/provider-connections/${victim.connection.id}`, 200)).body
      expect(connectionAfterUpdate).toMatchObject({
        id: connectionBefore.id,
        version: connectionBefore.version,
        status: connectionBefore.status,
        baseUrl: connectionBefore.baseUrl,
      })

      await assertApi(attackerContext, attacker.token, `${attacker.email}: foreign Provider Connection activation rejected`,
        'POST', `/provider-connections/${victim.connection.id}/activate`, 404)
      const connectionAfterActivate = (await assertApi(ownerContext, victim.token, `${victim.email}: Connection unchanged after activation denial`,
        'GET', `/provider-connections/${victim.connection.id}`, 200)).body
      expect(connectionAfterActivate).toMatchObject({ id: connectionBefore.id, version: connectionBefore.version, status: connectionBefore.status })

      await assertApi(attackerContext, attacker.token, `${attacker.email}: foreign Provider Connection revision rejected`,
        'POST', `/provider-connections/${victim.connection.id}/revisions`, 404)
      const connectionAfterRevision = (await assertApi(ownerContext, victim.token, `${victim.email}: Connection unchanged after revision denial`,
        'GET', `/provider-connections/${victim.connection.id}`, 200)).body
      expect(connectionAfterRevision).toMatchObject({ id: connectionBefore.id, version: connectionBefore.version, status: connectionBefore.status })

      const credentialsBefore = (await assertApi(ownerContext, victim.token, `${victim.email}: Credential state before hostile writes`,
        'GET', `/provider-connections/${victim.connection.id}/credentials`, 200)).body
      const hostileSecret = `m22-cross-secret-${suffix}-${attacker === orgA ? 'a' : 'b'}`
      await assertApi(attackerContext, attacker.token, `${attacker.email}: foreign Credential create rejected`,
        'POST', `/provider-connections/${victim.connection.id}/credentials`, 404, {
          rawSecret: hostileSecret,
          safeLabel: `M22 unauthorized ${suffix}`,
        })
      const credentialsAfterCreate = (await assertApi(ownerContext, victim.token, `${victim.email}: Credentials unchanged after create denial`,
        'GET', `/provider-connections/${victim.connection.id}/credentials`, 200)).body
      expect(credentialsAfterCreate).toEqual(credentialsBefore)
      expect(JSON.stringify(credentialsAfterCreate)).not.toContain(hostileSecret)

      await assertApi(attackerContext, attacker.token, `${attacker.email}: foreign Credential revoke rejected`,
        'POST', `/provider-connections/${victim.connection.id}/credentials/${victim.credential.id}/revoke`, 404)
      const credentialsAfterRevoke = (await assertApi(ownerContext, victim.token, `${victim.email}: Credential unchanged after revoke denial`,
        'GET', `/provider-connections/${victim.connection.id}/credentials`, 200)).body
      expect(credentialsAfterRevoke).toEqual(credentialsBefore)
      expect(JSON.stringify(credentialsAfterRevoke)).not.toContain(victim.rawSecret)

      const budgetBefore = (await assertApi(ownerContext, victim.token, `${victim.email}: Budget state before hostile write`,
        'GET', `/budgets/${victim.budget.id}`, 200)).body
      await assertApi(attackerContext, attacker.token, `${attacker.email}: foreign Budget update rejected`,
        'PUT', `/budgets/${victim.budget.id}`, 404, {
          totalAmount: '9999.00000000',
          expectedVersion: victim.budget.version,
        })
      const budgetAfter = (await assertApi(ownerContext, victim.token, `${victim.email}: Budget unchanged after denial`,
        'GET', `/budgets/${victim.budget.id}`, 200)).body
      expect(budgetAfter).toMatchObject({
        id: budgetBefore.id,
        totalAmount: budgetBefore.totalAmount,
        version: budgetBefore.version,
        status: budgetBefore.status,
        billingPeriodId: budgetBefore.billingPeriodId,
        scopeId: budgetBefore.scopeId,
      })

      const projectsBefore = (await assertApi(ownerContext, victim.token, `${victim.email}: Project state before hostile write`,
        'GET', '/projects?status=ACTIVE&page=0&size=100', 200)).body.items as Array<Record<string, unknown>>
      const victimProjectBefore = projectsBefore.find((entry) => String(entry.id) === victim.project.id)
      expect(victimProjectBefore).toBeTruthy()
      await assertApi(attackerContext, attacker.token, `${attacker.email}: foreign Project update rejected`,
        'PATCH', `/projects/${victim.project.id}`, 404, { name: `M22 unauthorized ${suffix}`, status: 'ARCHIVED' })
      const projectsAfter = (await assertApi(ownerContext, victim.token, `${victim.email}: Project unchanged after denial`,
        'GET', '/projects?status=ACTIVE&page=0&size=100', 200)).body.items as Array<Record<string, unknown>>
      expect(projectsAfter.find((entry) => String(entry.id) === victim.project.id)).toMatchObject({
        id: victimProjectBefore!.id,
        code: victimProjectBefore!.code,
        name: victimProjectBefore!.name,
        status: victimProjectBefore!.status,
      })

      const policyBefore = (await assertApi(ownerContext, victim.token, `${victim.email}: Routing Policy before hostile writes`,
        'GET', `/routing-policies/${victim.policy.id}`, 200)).body
      const hostileCandidate = [{
        providerAccountId: Number(attacker.providerAccount.id),
        providerModelId: Number(attacker.model.providerModelId),
        priority: 0,
        status: 'ACTIVE',
      }]
      await assertApi(attackerContext, attacker.token, `${attacker.email}: foreign Routing Policy update rejected`,
        'PUT', `/routing-policies/${victim.policy.id}`, 404, { candidates: hostileCandidate })
      const policyAfterUpdate = (await assertApi(ownerContext, victim.token, `${victim.email}: Routing Policy unchanged after update denial`,
        'GET', `/routing-policies/${victim.policy.id}`, 200)).body
      expect(policyAfterUpdate).toEqual(policyBefore)
      await assertApi(attackerContext, attacker.token, `${attacker.email}: foreign Routing Policy activation rejected`,
        'POST', `/routing-policies/${victim.policy.id}/activate`, 404)
      const policyAfterActivate = (await assertApi(ownerContext, victim.token, `${victim.email}: Routing Policy unchanged after activation denial`,
        'GET', `/routing-policies/${victim.policy.id}`, 200)).body
      expect(policyAfterActivate).toEqual(policyBefore)
      await assertApi(attackerContext, attacker.token, `${attacker.email}: foreign Routing Policy revision rejected`,
        'POST', `/routing-policies/${victim.policy.id}/revisions`, 404)
      const policyAfterRevision = (await assertApi(ownerContext, victim.token, `${victim.email}: Routing Policy unchanged after revision denial`,
        'GET', `/routing-policies/${victim.policy.id}`, 200)).body
      expect(policyAfterRevision).toEqual(policyBefore)

      const pricingBefore = (await assertApi(ownerContext, victim.token, `${victim.email}: Pricing Version state before hostile writes`,
        'GET', '/pricing-versions', 200)).body as Array<Record<string, unknown>>
      const victimPricingBefore = pricingBefore.find((entry) => String(entry.id) === victim.pricing.id)
      expect(victimPricingBefore).toBeTruthy()
      await assertApi(attackerContext, attacker.token, `${attacker.email}: foreign Pricing Version activation rejected`,
        'POST', `/pricing-versions/${victim.pricing.id}/activate`, 404)
      const pricingAfter = (await assertApi(ownerContext, victim.token, `${victim.email}: Pricing Version unchanged after activation denial`,
        'GET', '/pricing-versions', 200)).body as Array<Record<string, unknown>>
      expect(pricingAfter.find((entry) => String(entry.id) === victim.pricing.id)).toEqual(victimPricingBefore)

      // This catches both model-list disclosure and cross-org references being
      // accepted when a caller combines its own account ID with a foreign model ID.
      const crossPricing = await assertApi(attackerContext, attacker.token, `${attacker.email}: foreign private Provider Model cannot be priced`,
        'POST', '/pricing-versions', 404, {
          providerAccountId: Number(attacker.providerAccount.id),
          providerModelId: Number(victim.model.providerModelId),
          currency: 'CNY',
          effectiveFrom: new Date(Date.now() - 60_000).toISOString(),
          rates: [{ dimensionCode: 'INPUT_TOKEN', unitQuantity: 1_000_000, unitPrice: 8.88 }],
        }, true)
      expect.soft(crossPricing.body?.code, 'foreign private Provider Models are privacy-hidden during Pricing creation')
        .toBe('RESOURCE_NOT_FOUND')
      const attackerPricingAfter = (await assertApi(attackerContext, attacker.token, `${attacker.email}: no foreign model Pricing Version persisted`,
        'GET', '/pricing-versions', 200)).body as Array<{ id: string; providerModelId: string }>
      expect.soft(attackerPricingAfter.some((entry) => entry.providerModelId === victim.model.providerModelId),
        'a failed foreign Pricing write must leave no cross-org association').toBe(false)
      const victimModelsAfter = (await assertApi(ownerContext, victim.token, `${victim.email}: private Provider Model still owned and visible`,
        'GET', '/provider-models', 200)).body as Array<{ id: string; providerModelName: string }>
      expect(victimModelsAfter.some((entry) => entry.id === victim.model.providerModelId
        && entry.providerModelName === victim.model.wireName)).toBe(true)

      const attackerDraftBefore = (await assertApi(attackerContext, attacker.token,
        `${attacker.email}: Routing Policy candidate draft before hostile write`,
        'GET', `/routing-policies/${attacker.candidateDraft.id}`, 200)).body
      await assertApi(attackerContext, attacker.token,
        `${attacker.email}: foreign Provider Account and Model cannot become a route candidate`,
        'PUT', `/routing-policies/${attacker.candidateDraft.id}`, 400, { candidates: [{
          providerAccountId: Number(victim.providerAccount.id),
          providerModelId: Number(victim.model.providerModelId),
          priority: 0,
          status: 'ACTIVE',
        }] })
      const attackerDraftAfter = (await assertApi(attackerContext, attacker.token,
        `${attacker.email}: candidate draft unchanged after foreign reference denial`,
        'GET', `/routing-policies/${attacker.candidateDraft.id}`, 200)).body
      expect(attackerDraftAfter).toEqual(attackerDraftBefore)

      const attackerProfileBefore = (await assertApi(attackerContext, attacker.token,
        `${attacker.email}: Advisor Profile before hostile write`,
        'GET', '/ai-advisor/profile', 200)).body
      await assertApi(attackerContext, attacker.token, `${attacker.email}: foreign Advisor Profile references rejected`,
        'PUT', '/ai-advisor/profile', 400, {
          providerModelId: Number(victim.model.providerModelId),
          projectId: Number(victim.project.id),
          financialScopeType: 'PROJECT',
          financialScopeId: Number(victim.project.id),
          budgetEnforcementMode: 'OPTIONAL',
        })
      const attackerProfileAfter = (await assertApi(attackerContext, attacker.token,
        `${attacker.email}: Advisor Profile unchanged after denial`,
        'GET', '/ai-advisor/profile', 200)).body
      expect(attackerProfileAfter).toEqual(attackerProfileBefore)

      const victimJobBefore = (await assertApi(ownerContext, victim.token,
        `${victim.email}: Advisor Job before hostile retry`,
        'GET', `/ai-advisor/explanations/${victim.advisorJob.id}`, 200)).body
      await assertApi(attackerContext, attacker.token,
        `${attacker.email}: foreign Advisor Job retry rejected`,
        'POST', `/ai-advisor/explanations/${victim.advisorJob.id}/retry`, 404)
      const victimJobAfter = (await assertApi(ownerContext, victim.token,
        `${victim.email}: Advisor Job unchanged after retry denial`,
        'GET', `/ai-advisor/explanations/${victim.advisorJob.id}`, 200)).body
      expect(victimJobAfter).toEqual(victimJobBefore)

      const victimChargeBefore = (await assertApi(ownerContext, victim.token, `${victim.email}: Cost state before hostile posting`,
        'GET', `/costs/charges/${victim.charge.id}`, 200)).body
      await assertApi(attackerContext, attacker.token, `${attacker.email}: foreign Cost posting rejected`,
        'POST', `/costs/charges/${victim.charge.id}/post`, 404)
      const victimChargeAfter = (await assertApi(ownerContext, victim.token, `${victim.email}: Cost unchanged after posting denial`,
        'GET', `/costs/charges/${victim.charge.id}`, 200)).body
      expect(victimChargeAfter).toEqual(victimChargeBefore)
      const victimPostingAfter = (await assertApi(ownerContext, victim.token, `${victim.email}: Ledger posting unchanged after denial`,
        'GET', `/ledger/postings/${victim.posting.id}`, 200)).body
      expect(victimPostingAfter).toMatchObject({ id: victim.posting.id, status: victim.posting.status })
    }

    // Lower-role identity from Org A knows Org B's exact connection ID but
    // cannot read or administer Provider Hub resources.
    const employeeEmail = `m22-employee-${suffix}@example.test`
    const employeePassword = `M22-E-${randomUUID()}!`
    await registerThroughUi(employeePage, employeeEmail, employeePassword, `M22 Employee ${suffix}`)
    const employeeLogin = await loginThroughUi(employeePage, employeeEmail, employeePassword)
    const employeeIdentity = await me(employeeContext, employeeLogin.accessToken)
    expect(employeeIdentity.organizationId.toString()).toBe(orgAId)
    expect(employeeIdentity.permissions).not.toContain('PROVIDER_ACCOUNT_MANAGE')
    await assertApi(employeeContext, employeeLogin.accessToken, 'employee with B Connection ID cannot read Provider Hub',
      'GET', `/provider-connections/${orgB.connection.id}`, 403)
    await assertApi(employeeContext, employeeLogin.accessToken, 'employee cannot activate B Connection',
      'POST', `/provider-connections/${orgB.connection.id}/activate`, 403)
    const ownerConnectionAfterEmployee = (await assertApi(contextB, orgB.token, 'Org B owner verifies employee had no effect',
      'GET', `/provider-connections/${orgB.connection.id}`, 200)).body
    expect(ownerConnectionAfterEmployee).toMatchObject({
      id: Number(orgB.connection.id),
      status: orgB.connection.status,
      version: orgB.connection.version,
      baseUrl: orgB.connection.baseUrl,
    })

    // Direct browser URL entry must show the API's privacy 404 without any of
    // Org A's connection names, labels, URLs, or synthetic credential value.
    await pageB.goto(`${E2E_BASE_URL}/settings/provider-connections`)
    await expect(pageB.getByRole('heading', { name: '连接配置', level: 1 })).toBeVisible()
    await expect(pageB.locator('body')).toContainText(`#${orgB.connection.id}`)
    await expect(pageB.locator('body')).not.toContainText(`#${orgA.connection.id}`)
    await expect(pageB.locator('body')).not.toContainText(orgA.providerAccount.displayName)
    await expect(pageB.locator('body')).not.toContainText(orgA.rawSecret)
    await pageB.addInitScript((forbiddenValues: string[]) => {
      const matches: string[] = []
      Object.defineProperty(window, '__m22ForbiddenTextMatches', { configurable: true, value: matches })
      const inspect = (text: string) => {
        for (const value of forbiddenValues) {
          if (value && text.includes(value) && !matches.includes(value)) matches.push(value)
        }
      }
      inspect(document.documentElement?.textContent ?? '')
      const observer = new MutationObserver((records) => {
        for (const record of records) {
          if (record.type === 'characterData') {
            inspect(record.oldValue ?? '')
            inspect(record.target.textContent ?? '')
          }
          for (const node of Array.from(record.addedNodes)) inspect(node.textContent ?? '')
        }
      })
      observer.observe(document, { childList: true, subtree: true, characterData: true, characterDataOldValue: true })
    }, [orgA.providerAccount.displayName, orgA.credential.safeLabel, orgA.rawSecret])
    const foreignDetailResponse = pageB.waitForResponse((response) =>
      response.url() === `${API_ROOT}/provider-connections/${orgA.connection.id}`
      && response.request().method() === 'GET')
    await pageB.goto(`${E2E_BASE_URL}/settings/provider-connections/${orgA.connection.id}`)
    expect((await foreignDetailResponse).status()).toBe(404)
    const transientForeignContent = await pageB.evaluate(() => (
      window as Window & { __m22ForbiddenTextMatches?: string[] }
    ).__m22ForbiddenTextMatches ?? [])
    expect(transientForeignContent).toEqual([])
    await expect(pageB.locator('body')).not.toContainText(orgA.providerAccount.displayName)
    await expect(pageB.locator('body')).not.toContainText(orgA.credential.safeLabel)
    await expect(pageB.locator('body')).not.toContainText(orgA.rawSecret)

    // Refresh is per-context. Logging out A clears only A's refresh cookie;
    // B reloads into B's identity and retains its own cookie/session.
    const logoutButton = pageA.getByRole('button', { name: '退出登录' })
    await pageA.goto(`${E2E_BASE_URL}/workbench`)
    await expect(logoutButton).toBeVisible()
    const logoutResponsePromise = pageA.waitForResponse((response) =>
      response.url().endsWith('/api/v1/auth/logout') && response.request().method() === 'POST')
    await logoutButton.click()
    const logoutResponse = await logoutResponsePromise
    statuses.push({ operation: 'context A logout', method: 'POST', route: '/auth/logout', expected: 204, actual: logoutResponse.status() })
    expect(logoutResponse.status()).toBe(204)
    await expect(pageA).toHaveURL(/\/login$/)
    expect((await contextA.cookies(`${E2E_BASE_URL}/api/v1/auth`))
      .some((cookie) => cookie.name === 'aicostops_refresh')).toBe(false)
    expect((await contextB.cookies(`${E2E_BASE_URL}/api/v1/auth`))
      .some((cookie) => cookie.name === 'aicostops_refresh' && cookie.value === cookieB!.value)).toBe(true)

    const refreshResponsePromise = pageB.waitForResponse((response) =>
      response.url().endsWith('/api/v1/auth/refresh') && response.request().method() === 'POST')
    await pageB.reload()
    const refreshResponse = await refreshResponsePromise
    statuses.push({ operation: 'context B refresh after A logout', method: 'POST', route: '/auth/refresh', expected: 200, actual: refreshResponse.status() })
    expect(refreshResponse.status()).toBe(200)
    const refreshedB = await refreshResponse.json() as LoginResponse
    const identityAfterRefresh = await me(contextB, refreshedB.accessToken)
    expect(identityAfterRefresh.id.toString()).toBe(identityB.id.toString())
    expect(identityAfterRefresh.organizationId.toString()).toBe(orgBId)
    await expect(pageB.getByRole('button', { name: '退出登录' })).toBeVisible()

    await test.info().attach('m22-http-status-matrix.json', {
      body: Buffer.from(JSON.stringify(statuses, null, 2)),
      contentType: 'application/json',
    })
    const sanitizedStatuses = statuses.map((entry) => ({
      ...entry,
      operation: entry.operation.replace(/^[^:]+:\s*/, ''),
      route: entry.route.replace(/([?&](?:scopeId|billingPeriodId|projectId|modelId)=)[^&]+/g, '$1:id'),
    }))
    console.info(`[M22] HTTP status matrix (${sanitizedStatuses.length} requests): ${JSON.stringify(sanitizedStatuses)}`)
  } finally {
    await Promise.all([contextA.close(), contextB.close(), employeeContext.close()])
  }
})
