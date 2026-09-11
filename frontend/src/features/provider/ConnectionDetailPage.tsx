import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Empty, Input } from 'antd'
import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { toProblemDetail, type ProblemDetail } from '../../api/problem'
import { useAuth } from '../auth/AuthSessionProvider'
import { hasPermission } from '../settings/permissions'
import { useAuthorizationMutation } from '../settings/useAuthorizationMutation'
import { formatDateTime } from '../intelligence/format'
import { providerHubApi } from './api/providerHubApi'
import { providerHubKeys } from './api/providerHubKeys'
import type { ProbeResult, ProviderConnection } from './api/providerHubTypes'
import { ConnectionStatusPill, KindPill, ProbePill } from './badges'
import '../../features/intelligence/v3-tokens.css'
import './provider.css'

export interface ConnectionDetailPreview {
  connection: ProviderConnection
  revisions: ProviderConnection[]
}

/** Connection profile detail: versions, credentials (masked), probe. preview is DEV-only. */
export function ConnectionDetailPage(props: { preview?: ConnectionDetailPreview }) {
  const params = useParams()
  const id = props.preview ? props.preview.connection.id : Number(params.id)
  const auth = useAuth()
  const queryClient = useQueryClient()
  const [problem, setProblem] = useState<ProblemDetail | null>(null)
  const [probeResult, setProbeResult] = useState<ProbeResult | null>(null)
  const [secret, setSecret] = useState('')
  const [safeLabel, setSafeLabel] = useState('')
  const preview = props.preview
  const canManage = hasPermission(auth.user?.permissions, 'PROVIDER_ACCOUNT_MANAGE')
  const validId = Number.isInteger(id) && (id as number) > 0

  const detailQuery = useQuery({ queryKey: providerHubKeys.connection(validId ? id : null), queryFn: () => providerHubApi.connection(id), enabled: !preview && validId, staleTime: 30_000 })
  const revisionsQuery = useQuery({ queryKey: providerHubKeys.revisions(validId ? id : null), queryFn: () => providerHubApi.revisions(id), enabled: !preview && validId, staleTime: 30_000 })
  const credentialsQuery = useQuery({ queryKey: providerHubKeys.credentials(validId ? id : null), queryFn: () => providerHubApi.credentials(id), enabled: !preview && validId, staleTime: 30_000 })
  const connection = preview?.connection ?? detailQuery.data ?? null
  const revisions = preview?.revisions ?? revisionsQuery.data ?? []
  const credentials = credentialsQuery.data ?? []
  const isLoading = !preview && (detailQuery.isLoading || revisionsQuery.isLoading)
  const loadProblem = !preview ? detailQuery.error ?? revisionsQuery.error ?? null : null
  const loadText = loadProblem ? ((toProblemDetail(loadProblem) as { detail?: string; title?: string }).detail ?? '请稍后重试。') : null
  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: providerHubKeys.connection(id) })
    void queryClient.invalidateQueries({ queryKey: providerHubKeys.revisions(id) })
    void queryClient.invalidateQueries({ queryKey: providerHubKeys.credentials(id) })
  }

  const lifecycle = useAuthorizationMutation({
    mutationFn: (action: 'revision' | 'activate') => action === 'revision' ? providerHubApi.createRevision(id) : providerHubApi.activate(id),
    onSuccess: () => { setProblem(null); invalidate() },
    onError: (error) => setProblem(toProblemDetail(error)),
  })
  const probe = useAuthorizationMutation({
    mutationFn: () => providerHubApi.probe(id),
    onSuccess: (result) => { setProblem(null); setProbeResult(result) },
    onError: (error) => setProblem(toProblemDetail(error)),
  })
  const credentialOp = useAuthorizationMutation({
    mutationFn: async (op: { kind: 'create' | 'rotate' | 'revoke'; credentialId?: number }) => {
      if (op.kind === 'revoke' && op.credentialId !== undefined) { await providerHubApi.revokeCredential(id, op.credentialId); return }
      if (op.kind === 'rotate') { await providerHubApi.rotateCredential(id, secret, safeLabel || 'rotated'); return }
      await providerHubApi.createCredential(id, secret, safeLabel || 'primary')
    },
    onSuccess: () => { setProblem(null); setSecret(''); invalidate() },
    onError: (error) => setProblem(toProblemDetail(error)),
  })

  return (
    <main className="v3-page">
      <header>
        <span className="v3-eyebrow">Model Providers · 连接</span>
        <h1>连接详情 #{preview ? preview.connection.id : params.id}</h1>
        <p className="v3-lede">版本化配置：DRAFT 可编辑，ACTIVE 与 RETIRED 不可变。密钥只存不展。</p>
        <div className="v3-actions"><Link to="/settings/providers">回 Gallery</Link></div>
      </header>
      {problem && <div role="alert"><Alert type="error" showIcon closable onClose={() => setProblem(null)} message="操作失败" description={(problem as { detail?: string }).detail ?? '请稍后重试。'} /></div>}
      {loadText && <div role="alert"><Alert type="error" showIcon message="加载失败" description={loadText} /></div>}
      {isLoading && <div className="v3-skeleton" role="status" aria-label="正在加载连接详情"><div className="v3-note" style={{ minHeight: 160 }} /></div>}
      {!isLoading && !loadProblem && !connection && <div style={{ marginTop: 20 }}><Empty description="连接不存在或无权查看。" /></div>}
      {connection && (
        <>
          <section className="v3-section" aria-labelledby="v3-conn-profile">
            <div className="v3-section-head"><h2 id="v3-conn-profile">配置档案</h2></div>
            <dl className="v3-evidence">
              <div><dt>状态</dt><dd><ConnectionStatusPill status={connection.status} /> <KindPill kind={connection.connectionKind} /></dd></div>
              <div><dt>模板 / 协议</dt><dd>{connection.templateCode} · {connection.protocolCode}</dd></div>
              <div><dt>端点</dt><dd>{connection.baseUrl}{connection.completionPath} · 模型 {connection.modelsPath}</dd></div>
              <div><dt>认证 / 网络</dt><dd>{connection.authType} · {connection.networkPolicy}</dd></div>
              <div><dt>超时</dt><dd>连接 {connection.connectTimeoutMs}ms · 响应 {connection.responseTimeoutMs}ms</dd></div>
              <div><dt>版本</dt><dd>v{connection.version} · 创建 {formatDateTime(connection.createdAt)}{connection.activatedAt ? ` · 激活 ${formatDateTime(connection.activatedAt)}` : ''}{connection.retiredAt ? ` · 退役 ${formatDateTime(connection.retiredAt)}` : ''}</dd></div>
            </dl>
          </section>
          <section className="v3-section" aria-labelledby="v3-conn-versions">
            <div className="v3-section-head"><h2 id="v3-conn-versions">版本与激活</h2><p>一次只有一个 ACTIVE 版本；激活自动退役旧版（单事务）。ACTIVE 不可编辑。</p></div>
            <ul className="v3-list">
              {[...revisions].sort((a, b) => b.version - a.version).map((r) => (
                <li key={r.id} className="v3-row v3-row-slim">
                  <div className="v3-row-main"><p className="v3-row-title">v{r.version} · 连接 #{r.id} <ConnectionStatusPill status={r.status} /></p></div>
                  {!preview && canManage && r.status === 'DRAFT' && (
                    <div className="v3-row-action"><button type="button" className="v3-link-button" disabled={lifecycle.isPending} onClick={() => lifecycle.mutate('activate')}>激活此版</button></div>
                  )}
                </li>
              ))}
            </ul>
            {!preview && canManage && (
              <div className="v3-actions">
                <button type="button" className="v3-link-button" disabled={lifecycle.isPending} onClick={() => lifecycle.mutate('revision')}>基于当前开新修订版</button>
                {lifecycle.isPending && <span role="status">正在提交…</span>}
              </div>
            )}
          </section>
          <section className="v3-section" aria-labelledby="v3-conn-probe">
            <div className="v3-section-head"><h2 id="v3-conn-probe">健康探测</h2><p>显式受控探测，使用已配置凭证与服务端 UA；不做后台高频轮询。</p></div>
            {!preview && canManage && <div className="v3-actions"><button type="button" className="v3-link-button" disabled={probe.isPending} onClick={() => probe.mutate()}>{probe.isPending ? '探测中…' : '执行探测'}</button></div>}
            {probeResult && <div className="v3-note" role="status"><ProbePill status={probeResult.status} /> {probeResult.errorCode ? ` · ${probeResult.errorCode}` : ''} · {formatDateTime(probeResult.checkedAt)}</div>}
            {preview && <div className="v3-note" role="status">预览模式：生产页调用真实探测接口。</div>}
          </section>
          <section className="v3-section" aria-labelledby="v3-conn-creds">
            <div className="v3-section-head"><h2 id="v3-conn-creds">凭证</h2><p>只展示标签与状态；原始密钥、密文、nonce 永不返回。</p></div>
            {credentialsQuery.isLoading && <div className="v3-note" role="status">正在加载凭证…</div>}
            <ul className="v3-list">
              {credentials.map((c) => (
                <li key={c.id} className="v3-row v3-row-slim">
                  <div className="v3-row-main">
                    <p className="v3-row-title">{c.safeLabel} <span className={`v3-pill ${c.status === 'ACTIVE' ? 'v3-pill-ok' : 'v3-pill-neutral'}`}>{c.status}</span></p>
                    <p className="v3-row-detail">{c.credentialType} · 创建 {formatDateTime(c.createdAt)}{c.rotatedAt ? ` · 轮换 ${formatDateTime(c.rotatedAt)}` : ''}{c.revokedAt ? ` · 吊销 ${formatDateTime(c.revokedAt)}` : ''}</p>
                  </div>
                  {!preview && canManage && c.status === 'ACTIVE' && (
                    <div className="v3-row-action"><button type="button" className="v3-link-button" disabled={credentialOp.isPending} onClick={() => credentialOp.mutate({ kind: 'revoke', credentialId: c.id })}>吊销</button></div>
                  )}
                </li>
              ))}
            </ul>
            {credentials.length === 0 && !credentialsQuery.isLoading && <div className="v3-note">暂无凭证标签。</div>}
            {!preview && canManage && (
              <div className="v3-form-grid" style={{ marginTop: 12 }}>
                <label>新密钥（仅发送一次，不回显）<Input.Password value={secret} onChange={(e) => setSecret(e.target.value)} aria-label="新密钥" autoComplete="new-password" /></label>
                <label>标签<Input value={safeLabel} onChange={(e) => setSafeLabel(e.target.value)} aria-label="凭证标签" placeholder="primary" /></label>
                <div className="full v3-govern-actions">
                  <button type="button" disabled={secret === '' || credentialOp.isPending} onClick={() => credentialOp.mutate({ kind: 'create' })}>新增凭证</button>
                  <button type="button" disabled={secret === '' || credentialOp.isPending} onClick={() => credentialOp.mutate({ kind: 'rotate' })}>轮换（吊销旧 ACTIVE）</button>
                </div>
              </div>
            )}
          </section>
        </>
      )}
    </main>
  )
}
