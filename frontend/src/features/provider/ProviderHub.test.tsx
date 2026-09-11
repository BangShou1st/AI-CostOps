import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuth } from '../auth/AuthSessionProvider'
import { connectionFixture, discoveryFixture, templateFixture } from './benchmark/providerFixture'
import { ConnectionDetailPage } from './ConnectionDetailPage'
import { ProviderGalleryPage } from './ProviderGalleryPage'
import { ProviderModelsPage } from './ProviderModelsPage'

vi.mock('../auth/AuthSessionProvider', () => ({ useAuth: vi.fn() }))

const mockedUseAuth = vi.mocked(useAuth)

function authAs(permissions: string[]) {
  mockedUseAuth.mockReturnValue({
    status: 'authenticated',
    user: { id: '1', email: 'a@e.com', displayName: 'A', organizationId: '1', organizationMemberId: '3', permissions },
    login: vi.fn(), refreshMe: vi.fn(), logout: vi.fn(),
  } as ReturnType<typeof useAuth>)
}

function shell(children: React.ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/bench']}>
        <Routes><Route path="/bench" element={children} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => vi.clearAllMocks())

describe('Provider Hub', () => {
  it('gallery separates built-in from custom and never implies routing readiness', () => {
    authAs(['PROVIDER_ACCOUNT_READ'])
    shell(<ProviderGalleryPage preview={{ templates: templateFixture, connections: connectionFixture }} />)
    expect(screen.getByRole('heading', { name: 'Provider Gallery', level: 1 })).toBeInTheDocument()
    expect(screen.getByText('内置')).toBeInTheDocument()
    expect(screen.getByText('自定义')).toBeInTheDocument()
    expect(screen.getByText(/连接健康 ≠ 生产路由就绪/)).toBeInTheDocument()
    expect(screen.queryByText(/sk-/)).not.toBeInTheDocument()
  })
  it('connection detail keeps secrets masked to labels', () => {
    authAs(['PROVIDER_ACCOUNT_READ'])
    shell(<ConnectionDetailPage preview={{ connection: connectionFixture[0], revisions: connectionFixture }} />)
    expect(screen.getByText(/只展示标签与状态/)).toBeInTheDocument()
    expect(screen.getByText(/原始密钥、密文、nonce 永不返回/)).toBeInTheDocument()
    expect(screen.queryByText(/sk-/)).not.toBeInTheDocument()
  })
  it('models separate availability, protocol, probe and pricing states', () => {
    authAs(['PROVIDER_ACCOUNT_READ'])
    shell(<ProviderModelsPage preview={{ connections: connectionFixture, rows: discoveryFixture, capabilities: {}, promotion: null }} />)
    expect(screen.getByRole('heading', { name: '模型目录', level: 1 })).toBeInTheDocument()
    expect(screen.getByText(/在线可用性 ≠ 协议兼容 ≠ 定价真相/)).toBeInTheDocument()
    expect(screen.getByText('mimo-v2.5-flash')).toBeInTheDocument()
    expect(screen.getAllByText('可用', { exact: true }).length).toBeGreaterThanOrEqual(1)
    expect(screen.getByText('不可用')).toBeInTheDocument()
    expect(screen.getByText('聊天兼容')).toBeInTheDocument()
    expect(screen.getByText('不支持')).toBeInTheDocument()
    expect(screen.getByText('探针通过')).toBeInTheDocument()
    expect(screen.getByText('探针失败')).toBeInTheDocument()
  })
})
