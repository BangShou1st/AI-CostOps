import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import { useAuth } from '../auth/AuthSessionProvider'
import { rulesApi, type AllocationRule } from './api/rulesApi'
import { settingsApi } from '../settings/api/settingsApi'
import { RulesPage } from './RulesPage'

vi.mock('../auth/AuthSessionProvider', () => ({ useAuth: vi.fn() }))
vi.mock('./api/rulesApi', () => ({
  rulesApi: { listRules: vi.fn(), createVersion: vi.fn(), archive: vi.fn() },
}))
vi.mock('../settings/api/settingsApi', () => ({
  settingsApi: { listProviderAccounts: vi.fn() },
}))

const mockedUseAuth = vi.mocked(useAuth)
const mockedRulesApi = vi.mocked(rulesApi)
const mockedSettingsApi = vi.mocked(settingsApi)

const RULE: AllocationRule = {
  id: '41',
  ruleKey: 'openai-key',
  version: 2,
  name: 'OpenAI API Key',
  providerCode: 'OPENAI',
  providerAccountId: null,
  matchHintType: 'PROVIDER_API_KEY',
  matchValue: 'key-abc-123',
  priority: 10,
  targetProjectId: '5',
  targetCostCenterId: null,
  targetTeamId: null,
  effectiveFrom: '2026-01-01T00:00:00Z',
  effectiveTo: null,
  status: 'ACTIVE',
  createdByMemberId: '3',
  createdAt: '2026-01-02T00:00:00Z',
}

function renderPage(permissions: string[]) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  mockedUseAuth.mockReturnValue({
    status: 'authenticated',
    user: {
      id: '1',
      email: 'admin@example.com',
      displayName: 'Admin',
      organizationId: '2',
      organizationMemberId: '3',
      permissions,
    },
    login: vi.fn(),
    refreshMe: vi.fn(),
    logout: vi.fn(),
  } as ReturnType<typeof useAuth>)
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter><RulesPage /></MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  mockedRulesApi.listRules.mockResolvedValue({
    items: [RULE],
    page: 0,
    size: 50,
    totalElements: 1,
    totalPages: 1,
  })
  mockedSettingsApi.listProviderAccounts.mockResolvedValue({
    items: [
      { id: '9', providerCode: 'OPENAI', displayName: 'Main account', externalAccountRef: null, metadata: {}, status: 'ACTIVE', createdAt: '', updatedAt: '' },
    ],
    page: 0, size: 200, totalElements: 1, totalPages: 1,
  })
})

describe('RulesPage', () => {
  it('renders the immutable rule versions with key/version/priority/target', async () => {
    renderPage(['ALLOCATION_RULE_MANAGE'])

    await waitFor(() => expect(screen.getByText('openai-key')).toBeInTheDocument())
    expect(screen.getByText('OpenAI API Key')).toBeInTheDocument()
    expect(screen.getByText('key-abc-123')).toBeInTheDocument()
    expect(screen.getByText('API Key')).toBeInTheDocument()
    expect(screen.getByText('项目 5')).toBeInTheDocument()
    expect(screen.getByText('ACTIVE')).toBeInTheDocument()
  })

  it('creates a new version through the real contract and refreshes', async () => {
    mockedRulesApi.createVersion.mockResolvedValue({ ...RULE, version: 3 })
    renderPage(['ALLOCATION_RULE_MANAGE'])

    await waitFor(() => expect(screen.getByRole('button', { name: '创建新版本' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: '创建新版本' }))

    fireEvent.change(screen.getByLabelText('规则键'), { target: { value: 'openai-key' } })
    fireEvent.change(screen.getByLabelText('名称'), { target: { value: 'OpenAI Key v3' } })
    fireEvent.change(screen.getByLabelText('供应商代码'), { target: { value: 'OPENAI' } })
    fireEvent.change(screen.getByLabelText('匹配值'), { target: { value: 'key-abc-123' } })
    fireEvent.change(screen.getByLabelText('目标 ID'), { target: { value: '5' } })
    fireEvent.change(screen.getByLabelText('生效时间'), { target: { value: '2026-01-01T00:00:00Z' } })

    fireEvent.click(screen.getByRole('button', { name: '创 建' }))

    await waitFor(() => expect(mockedRulesApi.createVersion).toHaveBeenCalledTimes(1))
    const [ruleKey, definition] = mockedRulesApi.createVersion.mock.calls[0]
    expect(ruleKey).toBe('openai-key')
    expect(definition.name).toBe('OpenAI Key v3')
    expect(definition.matchHintType).toBe('PROVIDER_PROJECT')
    expect(definition.priority).toBe(100)
    expect(definition.targetProjectId).toBe('5')
    expect(definition.effectiveFrom).toBe('2026-01-01T00:00:00Z')
    await waitFor(() => expect(mockedRulesApi.listRules).toHaveBeenCalledTimes(2))
  })

  it('keeps the exact matchValue characters including leading and trailing spaces', async () => {
    mockedRulesApi.createVersion.mockResolvedValue(RULE)
    renderPage(['ALLOCATION_RULE_MANAGE'])

    await waitFor(() => expect(screen.getByRole('button', { name: '创建新版本' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: '创建新版本' }))

    fireEvent.change(screen.getByLabelText('规则键'), { target: { value: 'glm-key' } })
    fireEvent.change(screen.getByLabelText('名称'), { target: { value: 'Exact rule' } })
    fireEvent.change(screen.getByLabelText('供应商代码'), { target: { value: 'GLM' } })
    fireEvent.change(screen.getByLabelText('匹配值'), { target: { value: ' abc ' } })
    fireEvent.change(screen.getByLabelText('目标 ID'), { target: { value: '5' } })
    fireEvent.change(screen.getByLabelText('生效时间'), { target: { value: '2026-01-01T00:00:00Z' } })
    fireEvent.click(screen.getByRole('button', { name: '创 建' }))

    await waitFor(() => expect(mockedRulesApi.createVersion).toHaveBeenCalledTimes(1))
    const [, definition] = mockedRulesApi.createVersion.mock.calls[0]
    expect(definition.matchValue).toBe(' abc ')
  })

  it('submits the selected optional provider account with the new version', async () => {
    mockedRulesApi.createVersion.mockResolvedValue({ ...RULE, version: 3 })
    renderPage(['ALLOCATION_RULE_MANAGE'])

    await waitFor(() => expect(screen.getByRole('button', { name: '创建新版本' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: '创建新版本' }))

    fireEvent.change(screen.getByLabelText('规则键'), { target: { value: 'openai-key' } })
    fireEvent.change(screen.getByLabelText('名称'), { target: { value: 'OpenAI Key v3' } })
    fireEvent.change(screen.getByLabelText('供应商代码'), { target: { value: 'OPENAI' } })
    fireEvent.change(screen.getByLabelText('匹配值'), { target: { value: 'key-abc-123' } })
    fireEvent.change(screen.getByLabelText('目标 ID'), { target: { value: '5' } })
    fireEvent.change(screen.getByLabelText('生效时间'), { target: { value: '2026-01-01T00:00:00Z' } })

    // The optional provider-account dropdown loads asynchronously.
    const accountSelect = await screen.findByLabelText('供应商账号（可选）')
    fireEvent.mouseDown(accountSelect)
    await waitFor(() => expect(screen.getByText('Main account（OPENAI）')).toBeInTheDocument())
    fireEvent.click(screen.getByText('Main account（OPENAI）'))

    fireEvent.click(screen.getByRole('button', { name: '创 建' }))

    await waitFor(() => expect(mockedRulesApi.createVersion).toHaveBeenCalledTimes(1))
    const [, definition] = mockedRulesApi.createVersion.mock.calls[0]
    expect(definition.providerAccountId).toBe('9')
  })

  it('keeps the form usable and stays null when provider accounts are forbidden', async () => {
    mockedSettingsApi.listProviderAccounts.mockRejectedValue({
      isAxiosError: true,
      response: { data: { title: 'Forbidden', status: 403, detail: 'No access', code: 'FORBIDDEN', traceId: 't' } },
    })
    mockedRulesApi.createVersion.mockResolvedValue(RULE)
    renderPage(['ALLOCATION_RULE_MANAGE'])

    await waitFor(() => expect(screen.getByRole('button', { name: '创建新版本' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: '创建新版本' }))

    fireEvent.change(screen.getByLabelText('规则键'), { target: { value: 'glm-key' } })
    fireEvent.change(screen.getByLabelText('名称'), { target: { value: 'GLM rule' } })
    fireEvent.change(screen.getByLabelText('供应商代码'), { target: { value: 'GLM' } })
    fireEvent.change(screen.getByLabelText('匹配值'), { target: { value: 'v' } })
    fireEvent.change(screen.getByLabelText('目标 ID'), { target: { value: '5' } })
    fireEvent.change(screen.getByLabelText('生效时间'), { target: { value: '2026-01-01T00:00:00Z' } })

    // The optional dropdown disappears; the page must not break.
    await waitFor(() => expect(screen.queryByLabelText('供应商账号（可选）')).not.toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: '创 建' }))

    await waitFor(() => expect(mockedRulesApi.createVersion).toHaveBeenCalledTimes(1))
    const [, definition] = mockedRulesApi.createVersion.mock.calls[0]
    expect(definition.providerAccountId).toBeNull()
  })

  it('archive calls the real contract and refreshes', async () => {
    mockedRulesApi.archive.mockResolvedValue({ ...RULE, status: 'ARCHIVED' })
    renderPage(['ALLOCATION_RULE_MANAGE'])

    // antd inserts a space between the two CJK characters of the button label.
    await waitFor(() => expect(screen.getByRole('button', { name: /归\s*档/ })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: /归\s*档/ }))

    await waitFor(() => expect(mockedRulesApi.archive).toHaveBeenCalledTimes(1))
    expect(mockedRulesApi.archive.mock.calls[0][0]).toBe('41')
    await waitFor(() => expect(mockedRulesApi.listRules).toHaveBeenCalledTimes(2))
  })

  it('displays the backend problem code on validation failure', async () => {
    mockedRulesApi.createVersion.mockRejectedValue({
      isAxiosError: true,
      response: {
        data: {
          title: 'Invalid rule definition',
          status: 400,
          detail: 'priority must be between 1 and 9999.',
          code: 'VALIDATION_FAILED',
          traceId: 't-3',
        },
      },
    })
    renderPage(['ALLOCATION_RULE_MANAGE'])

    await waitFor(() => expect(screen.getByRole('button', { name: '创建新版本' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: '创建新版本' }))
    fireEvent.change(screen.getByLabelText('规则键'), { target: { value: 'bad' } })
    fireEvent.change(screen.getByLabelText('名称'), { target: { value: 'Bad' } })
    fireEvent.change(screen.getByLabelText('供应商代码'), { target: { value: 'GLM' } })
    fireEvent.change(screen.getByLabelText('匹配值'), { target: { value: 'v' } })
    fireEvent.change(screen.getByLabelText('目标 ID'), { target: { value: '5' } })
    fireEvent.change(screen.getByLabelText('生效时间'), { target: { value: '2026-01-01T00:00:00Z' } })
    fireEvent.click(screen.getByRole('button', { name: '创 建' }))

    await waitFor(() => {
      expect(screen.getByText(/Invalid rule definition（VALIDATION_FAILED）/)).toBeInTheDocument()
    })
    expect(screen.getByText('priority must be between 1 and 9999.')).toBeInTheDocument()
  })

  it('shows the normalized problem detail when the rule list fails instead of an empty list', async () => {
    mockedRulesApi.listRules.mockRejectedValue({
      isAxiosError: true,
      response: {
        data: {
          title: 'Forbidden',
          status: 403,
          detail: 'Access to this resource is forbidden.',
          code: 'FORBIDDEN',
          traceId: 't-10',
        },
      },
    })
    renderPage(['ALLOCATION_RULE_MANAGE'])

    await waitFor(() => {
      expect(screen.getByText(/访问被拒绝（FORBIDDEN）/)).toBeInTheDocument()
    })
    expect(screen.getByText('您没有访问此资源的权限。如您认为这是误判，请联系管理员。')).toBeInTheDocument()
    // The failure must not silently render as an empty rule list.
    expect(screen.queryByText('openai-key')).not.toBeInTheDocument()
    expect(screen.queryByText('暂无数据')).not.toBeInTheDocument()
  })

  it('without ALLOCATION_RULE_MANAGE shows the permission warning', () => {
    renderPage(['COST_READ'])

    expect(screen.getByText('缺少 ALLOCATION_RULE_MANAGE 权限')).toBeInTheDocument()
  })
})
