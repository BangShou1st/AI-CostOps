import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Drawer, Empty, Input, InputNumber, Select } from 'antd'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { toProblemDetail, type ProblemDetail } from '../../api/problem'
import { useAuth } from '../auth/AuthSessionProvider'
import { hasPermission } from '../auth/permissions'
import { useAuthorizationMutation } from '../settings/useAuthorizationMutation'
import { providerHubApi } from './api/providerHubApi'
import { providerHubKeys } from './api/providerHubKeys'
import type { ProbeResult, ProviderConnection, ProviderTemplate } from './api/providerHubTypes'
import { ConnectionStatusPill, KindPill, ProbePill, ProviderLettermark } from './badges'
import '../../features/intelligence/v3-tokens.css'
import './provider.css'

export interface GalleryPreview {
  templates: ProviderTemplate[]
  connections: ProviderConnection[]
}

/** Provider Gallery: readiness matrix by template. preview is DEV-only. */
export function ProviderGalleryPage(props: { preview?: GalleryPreview }) {
  const auth = useAuth()
  const queryClient = useQueryClient()
  const [createOpen, setCreateOpen] = useState(false)
  const [problem, setProblem] = useState<ProblemDetail | null>(null)
  const [probes, setProbes] = useState<Record<number, ProbeResult>>({})
  const [probing, setProbing] = useState<number | null>(null)
  const preview = props.preview
  const canManage = hasPermission(auth.user?.permissions, 'PROVIDER_ACCOUNT_MANAGE')

  const templatesQuery = useQuery({ queryKey: providerHubKeys.templates(), queryFn: () => providerHubApi.templates(), enabled: !preview, staleTime: 60_000 })
  const connectionsQuery = useQuery({ queryKey: providerHubKeys.connections(0, 50), queryFn: () => providerHubApi.connections(0, 50), enabled: !preview, staleTime: 30_000 })
  const templates = preview?.templates ?? templatesQuery.data ?? []
  const connections = preview?.connections ?? connectionsQuery.data?.items ?? []
  const isLoading = !preview && (templatesQuery.isLoading || connectionsQuery.isLoading)
  const loadError = !preview ? templatesQuery.error ?? connectionsQuery.error ?? null : null
  const loadProblem = loadError ? (toProblemDetail(loadError) as { title?: string; detail?: string }) : null

  const probe = async (id: number) => {
    setProbing(id)
    try {
      const result = await providerHubApi.probe(id)
      setProbes((prev) => ({ ...prev, [id]: result }))
      void queryClient.invalidateQueries({ queryKey: providerHubKeys.connections(0, 50) })
    } catch (error) {
      setProblem(toProblemDetail(error))
    } finally {
      setProbing(null)
    }
  }

  return (
    <main className="v3-page">
      <header>
        <span className="v3-eyebrow">Model Providers · 模型服务商</span>
        <h1>Provider Gallery</h1>
        <p className="v3-lede">连接可用不代表可正常路由，请确认模型与定价配置。</p>
        <dl className="v3-meta">
          <div><dt>模板</dt><dd>{templates.length}</dd></div>
          <div><dt>连接</dt><dd>{connections.length}</dd></div>
        </dl>
      </header>
      <div className="v3-govern-banner" role="note">
        还需确认凭证、模型与定价均已启用。
      </div>
      {problem && <div role="alert"><Alert type="error" showIcon closable onClose={() => setProblem(null)} title="操作失败" description={(problem as { detail?: string }).detail ?? '请稍后重试。'} /></div>}
      {loadProblem && <div role="alert"><Alert type="error" showIcon title={loadProblem.title ?? '加载失败'} description={loadProblem.detail ?? '请稍后重试。'} /></div>}
      {isLoading && <div className="v3-skeleton" role="status" aria-label="正在加载服务商总览"><div className="v3-provider-group" style={{ minHeight: 180 }} /></div>}
      {!isLoading && !loadProblem && templates.length === 0 && <div style={{ marginTop: 20 }}><Empty description="暂无服务商模板。" /></div>}
      {!preview && canManage && (
        <div className="v3-actions"><Button type="primary" onClick={() => { setProblem(null); setCreateOpen(true) }}>新建连接</Button></div>
      )}
      {templates.map((t) => {
        const rows = connections.filter((c) => c.templateCode === t.code)
        return (
          <section key={t.code} className="v3-provider-group" aria-label={t.name}>
            <div className="v3-provider-group-head">
              <ProviderLettermark name={t.name} kind={t.connectionKind} />
              <div><div className="t">{t.name}</div><div className="s">{t.protocolCode} · {t.networkPolicy}</div></div>
              <KindPill kind={t.connectionKind} />
            </div>
            {rows.length === 0 && <div className="v3-provider-conn"><div className="detail">该模板下暂无连接配置。</div></div>}
            {rows.map((c) => (
              <div key={c.id} className="v3-provider-conn">
                <div className="main">
                  <div className="title">连接 #{c.id} · v{c.version} <ConnectionStatusPill status={c.status} /></div>
                  <div className="detail">{c.baseUrl} · {c.authType} · 超时 {c.connectTimeoutMs}/{c.responseTimeoutMs}ms</div>
                </div>
                <ProbePill status={probes[c.id]?.status ?? null} />
                {!preview && canManage && c.status === 'ACTIVE' && (
                  <button type="button" className="v3-link-button" disabled={probing === c.id} onClick={() => void probe(c.id)}>{probing === c.id ? '探测中…' : '探测'}</button>
                )}
                <Link to={`/settings/provider-connections/${c.id}`}>详情</Link>
              </div>
            ))}
          </section>
        )
      })}
      <Drawer open={createOpen} onClose={() => setCreateOpen(false)} title="新建连接（DRAFT）" width={520}>
        <ConnectionCreateForm templates={templates} onDone={() => { setCreateOpen(false); void queryClient.invalidateQueries({ queryKey: providerHubKeys.connections(0, 50) }) }} onError={setProblem} />
      </Drawer>
    </main>
  )
}

export function ConnectionCreateForm(props: { templates: ProviderTemplate[]; onDone: () => void; onError: (p: ProblemDetail) => void }) {
  const [templateCode, setTemplateCode] = useState(props.templates[0]?.code ?? '')
  const [providerAccountId, setProviderAccountId] = useState<number | null>(null)
  const [baseUrl, setBaseUrl] = useState('')
  const template = props.templates.find((t) => t.code === templateCode)
  const locked = new Set(template?.lockedFields ?? [])
  const create = useAuthorizationMutation({
    mutationFn: () => providerHubApi.createConnection({
      templateCode: templateCode || undefined,
      providerAccountId: providerAccountId ?? undefined,
      baseUrl: locked.has('baseUrl') ? undefined : baseUrl || undefined,
    }),
    onSuccess: () => props.onDone(),
    onError: (error) => props.onError(toProblemDetail(error)),
  })
  return (
    <div className="v3-form-grid">
      <label className="full">模板
        <Select value={templateCode} onChange={setTemplateCode} aria-label="连接模板" options={props.templates.map((t) => ({ value: t.code, label: `${t.name}（${t.connectionKind}）` }))} />
      </label>
      {template && <div className="v3-note full">以下字段由模板固定：{template.lockedFields.length > 0 ? template.lockedFields.join('、') : '无'}</div>}
      <label>Provider Account ID<InputNumber value={providerAccountId} onChange={(v) => setProviderAccountId(typeof v === 'number' ? v : null)} min={1} precision={0} aria-label="Provider Account ID" /></label>
      <label>Base URL<Input value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} disabled={locked.has('baseUrl')} aria-label="Base URL" placeholder={locked.has('baseUrl') ? '内置模板锁定' : 'https://…'} /></label>
      <div className="full v3-govern-actions">
        <button type="button" disabled={create.isPending} onClick={() => create.mutate()}>创建 DRAFT</button>
        {create.isPending && <span role="status">正在创建…</span>}
      </div>
      <div className="v3-note full">创建后为草稿，激活前不可用。</div>
    </div>
  )
}
