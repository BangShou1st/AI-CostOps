import type { APIRequestContext } from '@playwright/test'
import { crc32 } from 'node:zlib'
import { randomUUID } from 'node:crypto'
import { API_ROOT } from './env'

/**
 * Minimal STORE-method ZIP writer. The DeepSeek adapter requires exactly one
 * amount-*.csv and one cost-*.csv inside a ZIP; zlib.crc32 gives us the CRC
 * needed to build a standards-compliant archive without adding a dependency.
 */
export function buildZip(entries: Array<{ name: string; content: string }>): Buffer {
  const parts = entries.map((entry) => ({
    name: Buffer.from(entry.name, 'utf8'),
    data: Buffer.from(entry.content, 'utf8'),
  }))
  const local: Buffer[] = []
  const central: Buffer[] = []
  let offset = 0
  for (const { name, data } of parts) {
    const crc = crc32(data) >>> 0
    const header = Buffer.alloc(30)
    header.writeUInt32LE(0x04034b50, 0)
    header.writeUInt16LE(20, 4)
    header.writeUInt16LE(0x0800, 6) // UTF-8 file names
    header.writeUInt16LE(0, 8)      // STORE (no compression)
    header.writeUInt32LE(crc, 14)
    header.writeUInt32LE(data.length, 18)
    header.writeUInt32LE(data.length, 22)
    header.writeUInt16LE(name.length, 26)
    local.push(header, name, data)

    const descriptor = Buffer.alloc(46)
    descriptor.writeUInt32LE(0x02014b50, 0)
    descriptor.writeUInt16LE(20, 4)
    descriptor.writeUInt16LE(20, 6)
    descriptor.writeUInt16LE(0x0800, 8)
    descriptor.writeUInt32LE(crc, 16)
    descriptor.writeUInt32LE(data.length, 20)
    descriptor.writeUInt32LE(data.length, 24)
    descriptor.writeUInt16LE(name.length, 28)
    descriptor.writeUInt32LE(offset, 42)
    central.push(descriptor, name)
    offset += header.length + name.length + data.length
  }
  const centralSize = central.reduce((sum, part) => sum + part.length, 0)
  const end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054b50, 0)
  end.writeUInt16LE(parts.length, 8)
  end.writeUInt16LE(parts.length, 10)
  end.writeUInt32LE(centralSize, 12)
  end.writeUInt32LE(offset, 16)
  return Buffer.concat([...local, ...central, end])
}

export function deepSeekFixtureZip(
  month: string,
  startIso: string,
  endIso: string,
  suffix: string,
): Buffer {
  const amountRows = [
    'user_id,start_time_iso,end_time_iso,model,api_key_name,api_key,type,price,amount',
    `synthetic-user,${startIso},${endIso},deepseek-chat,e2e-key-${suffix},sk-SECRET-SENTINEL-DO-NOT-PERSIST,api_call,0.000002,125`,
  ]
  const costRows = [
    'user_id,start_time_iso,end_time_iso,model,wallet_type,cost,currency',
    `synthetic-user,${startIso},${endIso},deepseek-chat,main_wallet,1.25,CNY`,
  ]
  return buildZip([
    { name: `amount-${month}.csv`, content: amountRows.join('\n') },
    { name: `cost-${month}.csv`, content: costRows.join('\n') },
  ])
}

export interface AuthBean {
  accessToken: string
  expiresIn: number
  user: { id: number; displayName: string }
}

export interface AuthUser {
  id: number
  email: string
  displayName: string
  organizationId: number
  organizationMemberId: number
  permissions: string[]
}

export interface ProviderAccount {
  id: number
  providerCode: string
  displayName: string
  status: string
}

export interface ImportBatch {
  id: number
  status: string
  latestAttemptId?: number | null
}

export interface ChargeSummary {
  id: number
  providerCode: string
  amount: string
  currency: string
  reviewStatus?: string | null
  periodStart?: string | null
  periodEnd?: string | null
}

export interface AllocationDecision {
  id: number
  status: string
  source: string
  lines: Array<{ id: number; allocatedAmount: string }>
}

export interface Expense {
  id: number
  status: string
  version: number
  amount: string
  currency: string
  expenseDate: string
}

export interface BillingPeriod {
  id: number
  status: string
  periodStart: string
  periodEnd: string
}

export interface ReconciliationCase {
  id: number
  status: string
  type?: string
}

export type CloseBlockerCode =
  | 'OPEN_IMPORTS'
  | 'UNRESOLVED_DUPLICATES'
  | 'UNALLOCATED_CHARGES'
  | 'UNPOSTED_APPROVED_EXPENSES'
  | 'OPEN_MATERIAL_RECONCILIATION'
  | 'PENDING_CORRECTIONS'
  | 'LEDGER_INTEGRITY'

export interface CloseCheck {
  blockerCode: CloseBlockerCode
  result: 'PASS' | 'FAIL' | 'ERROR'
  itemCount: number
  summary?: Record<string, unknown> | null
}

export interface CloseReadiness {
  ready: boolean
  checks: CloseCheck[]
}

/** API helpers used only to create prerequisites the browser flow is not testing. */
export class ApiClient {
  readonly root: string

  constructor(private readonly ctx: APIRequestContext) {
    this.root = API_ROOT
  }

  async login(email: string, password: string): Promise<AuthBean> {
    const response = await this.ctx.post(`${this.root}/auth/login`, {
      data: { email, password },
    })
    return this.expectOk(response, 'auth/login')
  }

  async me(token: string): Promise<AuthUser> {
    return this.json('get', '/auth/me', token)
  }

  async createProviderAccount(
    token: string,
    input: { providerCode: string; displayName: string; externalAccountRef: string },
  ): Promise<ProviderAccount> {
    return this.json('post', '/provider-accounts', token, input)
  }

  async uploadProviderImport(
    token: string,
    zip: Buffer,
    providerAccountId: number,
  ): Promise<ImportBatch & { latestAttemptId: number | null }> {
    const response = await this.ctx.post(`${this.root}/provider-imports`, {
      headers: { Authorization: `Bearer ${token}` },
      multipart: {
        file: { name: 'synthetic-deepseek.zip', mimeType: 'application/zip', buffer: zip },
        providerAccountId: String(providerAccountId),
        sourceType: 'FILE_EXPORT',
      },
    })
    return this.expectOk(response, 'provider-imports')
  }

  async getImport(token: string, importId: number): Promise<ImportBatch> {
    return this.json('get', `/imports/${importId}`, token)
  }

  async confirmImport(token: string, importId: number): Promise<ImportBatch> {
    return this.json('post', `/imports/${importId}/confirm`, token)
  }

  async listCharges(token: string): Promise<{ items: ChargeSummary[] }> {
    return this.json('get', '/costs/charges?page=0&size=100', token)
  }

  async getCharge(token: string, chargeId: number): Promise<ChargeSummary> {
    return this.json('get', `/costs/charges/${chargeId}`, token)
  }

  async listDecisionsForCharge(token: string, chargeId: number): Promise<AllocationDecision[]> {
    return this.json('get', `/costs/charges/${chargeId}/allocation-decisions`, token)
  }

  async listDecisionsForExpense(token: string, expenseId: number): Promise<AllocationDecision[]> {
    return this.json('get', `/expenses/${expenseId}/allocation-decisions`, token)
  }

  async createManualAllocation(
    token: string,
    expense: { expenseId?: number; chargeId?: number },
    lines: Array<{ allocatedAmount: string; currency: string; projectId?: number }>,
  ): Promise<AllocationDecision> {
    const path = expense.expenseId
      ? `/expenses/${expense.expenseId}/allocation-decisions/manual`
      : `/costs/charges/${expense.chargeId}/allocation-decisions/manual`
    return this.json('post', path, token, { lines })
  }

  async confirmAllocation(token: string, decisionId: number): Promise<AllocationDecision> {
    return this.json('post', `/allocation-decisions/${decisionId}/confirm`, token)
  }

  async createProject(
    token: string,
    input: { name: string; code: string },
  ): Promise<{ id: number }> {
    return this.json('post', '/projects', token, input)
  }

  async listAllocationTargets(token: string): Promise<
    Array<{ type: 'PROJECT' | 'COST_CENTER' | 'TEAM'; id: string; name: string }>
  > {
    return this.json('get', '/allocation-targets', token)
  }

  async createExpense(
    token: string,
    input: { expenseDate: string; amount: string; currency: string },
  ): Promise<Expense> {
    return this.json('post', '/expenses', token, input)
  }

  async uploadExpenseEvidence(token: string, expenseId: number, expectedVersion: number): Promise<void> {
    const response = await this.ctx.post(`${this.root}/expenses/${expenseId}/evidence`, {
      headers: { Authorization: `Bearer ${token}` },
      multipart: {
        file: {
          name: 'synthetic-receipt.txt',
          mimeType: 'text/plain',
          buffer: Buffer.from('synthetic expense evidence; no real user data', 'utf8'),
        },
        expectedVersion: String(expectedVersion),
      },
    })
    this.expectOk(response, `expenses/${expenseId}/evidence`)
  }

  async submitExpense(token: string, expenseId: number, expectedVersion: number): Promise<Expense> {
    return this.json('post', `/expenses/${expenseId}/submit`, token, { expectedVersion })
  }

  async getExpense(token: string, expenseId: number): Promise<Expense> {
    return this.json('get', `/expenses/${expenseId}`, token)
  }

  async listExpenseReviews(
    token: string,
    status?: string,
  ): Promise<{ items: Array<Expense & { postingReady: boolean }> }> {
    const query = new URLSearchParams({ page: '0', size: '100' })
    if (status) query.set('status', status)
    return this.json('get', `/expense-reviews?${query.toString()}`, token)
  }

  async approveExpense(token: string, expenseId: number, expectedVersion: number): Promise<Expense> {
    return this.json('post', `/expenses/${expenseId}/approve`, token, { expectedVersion })
  }

  async postExpense(token: string, expenseId: number): Promise<void> {
    return this.json('post', `/expenses/${expenseId}/post`, token, { commitmentLinks: [] })
  }

  async listBillingPeriods(token: string): Promise<BillingPeriod[]> {
    return this.json('get', '/billing-periods', token)
  }

  async createBudget(
    token: string,
    input: {
      billingPeriodId: number
      scopeType: string
      scopeId: number
      currency: string
      totalAmount: string
    },
  ): Promise<{ id: number; status: string }> {
    return this.json('post', '/budgets', token, input)
  }

  async requestCommitment(
    token: string,
    budgetId: number,
    amount: string,
  ): Promise<{ id: number; status: string }> {
    return this.json('post', `/budgets/${budgetId}/commitments`, token, { amount, currency: 'CNY' })
  }

  async approveCommitment(token: string, commitmentId: number): Promise<{ id: number; status: string }> {
    return this.json('post', `/commitments/${commitmentId}/approve`, token)
  }

  async runReconciliation(token: string, billingPeriodId: number): Promise<{ id: number }> {
    return this.json('post', '/reconciliation-runs', token, { billingPeriodId: String(billingPeriodId) })
  }

  async listReconciliationCases(
    token: string,
    options: { runId?: number; status?: string } = {},
  ): Promise<{ items: ReconciliationCase[] }> {
    const query = new URLSearchParams({ page: '0', size: '100' })
    if (options.runId) query.set('runId', String(options.runId))
    if (options.status) query.set('status', options.status)
    return this.json('get', `/reconciliation-cases?${query.toString()}`, token)
  }

  async investigateCase(token: string, caseId: number): Promise<void> {
    return this.json('post', `/reconciliation-cases/${caseId}/investigate`, token)
  }

  async resolveCase(
    token: string,
    caseId: number,
    input: { reasonCode: string; resolutionNote: string },
  ): Promise<void> {
    return this.json('post', `/reconciliation-cases/${caseId}/resolve`, token, input)
  }

  async closeReadiness(token: string, periodId: number): Promise<CloseReadiness> {
    return this.json('get', `/billing-periods/${periodId}/close-readiness`, token)
  }

  async closePeriod(token: string, periodId: number): Promise<{ periodStatus: string }> {
    return this.json('post', `/billing-periods/${periodId}/close`, token)
  }

  async reopenPeriod(
    token: string,
    periodId: number,
    input: { reasonCode: string; reasonNote: string },
  ): Promise<{ periodStatus: string }> {
    return this.json('post', `/billing-periods/${periodId}/reopen`, token, input)
  }

  private async json(
    method: 'get' | 'post' | 'put' | 'patch',
    path: string,
    token: string,
    body?: unknown,
    // The wire shape is validated by the caller's declared return type; the
    // backend JSON is naturally loosely typed at this deserialization boundary.
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
  ): Promise<any> {
    const headers: Record<string, string> = { Authorization: `Bearer ${token}` }
    if (method !== 'get') {
      headers['Idempotency-Key'] = randomUUID()
    }
    const response = await this.ctx[method](`${this.root}${path}`, { headers, data: body })
    return this.expectOk(response, path)
  }

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  private async expectOk(response: Awaited<ReturnType<APIRequestContext['get']>>, label: string): Promise<any> {
    if (!response.ok()) {
      const body = await response.text().catch(() => '')
      throw new Error(`${label} failed with ${response.status()}: ${body}`)
    }
    if (response.status() === 204) {
      return null
    }
    return response.json()
  }
}