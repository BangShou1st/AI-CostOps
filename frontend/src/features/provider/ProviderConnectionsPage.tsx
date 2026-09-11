import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Drawer, Empty, Table } from 'antd'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { toProblemDetail, type ProblemDetail } from '../../api/problem'
import { useAuth } from '../auth/AuthSessionProvider'
import { hasPermission } from '../settings/permissions'
import { providerHubApi } from './api/providerHubApi'
import { providerHubKeys } from './api/providerHubKeys'
import { ConnectionCreateForm } from './ProviderGalleryPage'
import { ConnectionStatusPill } from './badges'
import { formatDateTime } from '../intelligence/format'
import '../../features/intelligence/v3-tokens.css'
import './provider.css'

/** Dense connections table with lifecycle entry points. preview is DEV-only. */
export function ProviderConnectionsPage(props: { preview?: { connections: import('./api/providerHubTypes').ProviderConnection[] } }) {
  const auth = useAuth()
  const queryClient = useQueryClient()
  const [page, setPage] = useState(0)
  const [createOpen, setCreateOpen] = useState(false)
  const [problem, setProblem] = useState<ProblemDetail | null>(null)
  const preview = props.preview
  const canManage = hasPermission(auth.user?.permissions, 'PROVIDER_ACCOUNT_MANAGE')
  const size = 50

  const listQuery = useQuery({ queryKey: providerHubKeys.connections(page, size), queryFn: () => providerHubApi.connections(page, size), enabled: !preview, staleTime: 30_000 })
  const templatesQuery = useQuery({ queryKey: providerHubKeys.templates(), queryFn: () => providerHubApi.templates(), enabled: !preview && createOpen, staleTime: 60_000 })
  const connections = preview?.connections ?? listQuery.data?.items ?? []
  const total = listQuery.data?.totalElements ?? connections.length
  const isLoading = !preview && listQuery.isLoading
  const loadProblem = !preview && listQuery.error ? (toProblemDetail(listQuery.error) as { title?: string; detail?: string }) : null

  return (
    <main className="v3-page">
      <header>
        <span className="v3-eyebrow">Model Providers · 连接</span>
        <h1>连接配置</h1>
        <p className="v3-lede">版本化连接档案。ACTIVE 不可编辑；改配置必须开新修订版再激活。</p>
        <dl className="v3-meta"><div><dt>连接</dt><dd>{total} 个</dd></div></dl>
      </header>
      {problem && <div role="alert"><Alert type="error" showIcon closable onClose={() => setProblem(null)} message="操作失败" description={(problem as { detail?: string }).detail ?? '请稍后重试。'} /></div>}
      {loadProblem && <div role="alert"><Alert type="error" showIcon message={loadProblem.title ?? '加载失败'} description={loadProblem.detail ?? '请稍后重试。'} /></div>}
      {!preview && canManage && <div className="v3-actions"><Button type="primary" onClick={() => { setProblem(null); setCreateOpen(true) }}>新建连接</Button></div>}
      {isLoading && <div className="v3-skeleton" role="status" aria-label="正在加载连接"><div className="v3-note" style={{ minHeight: 200 }} /></div>}
      {!isLoading && !loadProblem && connections.length === 0 && <div style={{ marginTop: 20 }}><Empty description="暂无连接配置。" /></div>}
      {connections.length > 0 && (
        <div style={{ marginTop: 16 }} className="v3-table-wrap">
          <Table
            rowKey="id"
            dataSource={connections}
            loading={isLoading}
            pagination={preview ? false : { current: page + 1, pageSize: size, total, onChange: (p) => setPage(p - 1) }}
            scroll={{ x: 980 }}
            columns={[
              { title: '连接', key: 'id', fixed: 'left' as const, width: 150, render: (_: unknown, c) => <span>#{c.id} · v{c.version}</span> },
              { title: '状态', dataIndex: 'status', key: 'status', width: 110, render: (s: string) => <ConnectionStatusPill status={s} /> },
              { title: '模板', dataIndex: 'templateCode', key: 'template', width: 170 },
              { title: '协议', dataIndex: 'protocolCode', key: 'protocol', width: 200, render: (v: string, c) => `${v} · ${c.networkPolicy}` },
              { title: '端点', dataIndex: 'baseUrl', key: 'endpoint', width: 260, ellipsis: true },
              { title: '认证', dataIndex: 'authType', key: 'auth', width: 110 },
              { title: '激活于', dataIndex: 'activatedAt', key: 'activated', width: 190, render: (v: string | null) => (v ? formatDateTime(v) : '—') },
              { title: '操作', key: 'actions', fixed: 'right' as const, width: 90, render: (_: unknown, c) => <Link to={`/settings/provider-connections/${c.id}`}>详情</Link> },
            ]}
          />
        </div>
      )}
      <Drawer open={createOpen} onClose={() => setCreateOpen(false)} title="新建连接（DRAFT）" width={520}>
        <ConnectionCreateForm templates={templatesQuery.data ?? []} onDone={() => { setCreateOpen(false); void queryClient.invalidateQueries({ queryKey: providerHubKeys.connections(page, size) }) }} onError={setProblem} />
      </Drawer>
    </main>
  )
}
