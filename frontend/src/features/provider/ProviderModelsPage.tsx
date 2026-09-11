import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Empty, Input, Select } from 'antd'
import { useState } from 'react'
import { toProblemDetail, type ProblemDetail } from '../../api/problem'
import { useAuth } from '../auth/AuthSessionProvider'
import { hasPermission } from '../settings/permissions'
import { formatDateTime } from '../intelligence/format'
import { providerHubApi } from './api/providerHubApi'
import { providerHubKeys } from './api/providerHubKeys'
import type { DiscoveryRow, ModelProbeCapabilities, PromotionResult, ProviderConnection } from './api/providerHubTypes'
import { AvailabilityPill, ProbePill } from './badges'
import '../../features/intelligence/v3-tokens.css'
import './provider.css'

export interface ModelsPreview {
  connections: ProviderConnection[]
  rows: DiscoveryRow[]
  capabilities: Record<number, ModelProbeCapabilities>
  promotion: PromotionResult | null
}

const PROTOCOL_TEXT: Record<string, string> = { CHAT_COMPLETIONS: '聊天兼容', UNSUPPORTED: '不支持', UNKNOWN: '未知' }

function protocolLabel(code: string): string {
  return PROTOCOL_TEXT[code] ?? code
}

/** Model catalog: availability vs protocol vs probe vs pricing, structurally separated. preview is DEV-only. */
export function ProviderModelsPage(props: { preview?: ModelsPreview }) {
  const auth = useAuth()
  const queryClient = useQueryClient()
  const preview = props.preview
  const canManage = hasPermission(auth.user?.permissions, 'PROVIDER_ACCOUNT_MANAGE')
  const [connectionId, setConnectionId] = useState<number | null>(preview?.connections[0]?.id ?? null)
  const [problem, setProblem] = useState<ProblemDetail | null>(null)
  const [manualName, setManualName] = useState('')
  const [caps, setCaps] = useState<Record<number, ModelProbeCapabilities>>(preview?.capabilities ?? {})
  const [promotion, setPromotion] = useState<PromotionResult | null>(preview?.promotion ?? null)
  const [working, setWorking] = useState<string | null>(null)

  const connectionsQuery = useQuery({ queryKey: providerHubKeys.connections(0, 50), queryFn: () => providerHubApi.connections(0, 50), enabled: !preview, staleTime: 30_000 })
  const connections = preview?.connections ?? connectionsQuery.data?.items ?? []
  const activeId = connectionId ?? connections.find((c) => c.status === 'ACTIVE')?.id ?? connections[0]?.id ?? null
  const modelsQuery = useQuery({ queryKey: providerHubKeys.models(activeId), queryFn: () => providerHubApi.models(activeId as number), enabled: !preview && activeId !== null, staleTime: 30_000 })
  const rows = preview?.rows ?? modelsQuery.data ?? []
  const isLoading = !preview && (connectionsQuery.isLoading || modelsQuery.isLoading)
  const loadError = !preview ? connectionsQuery.error ?? modelsQuery.error ?? null : null
  const loadProblem = loadError ? (toProblemDetail(loadError) as { title?: string; detail?: string }) : null
  const invalidate = () => { if (activeId !== null) void queryClient.invalidateQueries({ queryKey: providerHubKeys.models(activeId) }) }

  const run = async (key: string, fn: () => Promise<unknown>) => {
    setWorking(key)
    try {
      const result = await fn()
      setProblem(null)
      return result
    } catch (error) {
      setProblem(toProblemDetail(error))
      return null
    } finally {
      setWorking(null)
    }
  }
  const refresh = () => activeId !== null && void run('refresh', async () => { await providerHubApi.refreshModels(activeId, true); invalidate() })
  const registerManual = () => activeId !== null && manualName.trim() !== '' && void run('manual', async () => { await providerHubApi.manualModel(activeId, manualName.trim()); setManualName(''); invalidate() })
  const probeRow = (row: DiscoveryRow) => activeId !== null && void run(`probe-${row.id}`, async () => {
    const result = await providerHubApi.probeModel(activeId, row.id)
    setCaps((prev) => ({ ...prev, [row.id]: result }))
    invalidate()
  })
  const promoteRow = (row: DiscoveryRow) => activeId !== null && void run(`promote-${row.id}`, async () => {
    setPromotion(await providerHubApi.promoteModel(activeId, row.id))
  })

  return (
    <main className="v3-page">
      <header>
        <span className="v3-eyebrow">Model Providers · 模型</span>
        <h1>模型目录</h1>
        <p className="v3-lede">在线可用性 ≠ 协议兼容 ≠ 定价真相。四层状态各自独立表达。</p>
      </header>
      <div className="v3-govern-banner" role="note">
        <strong>发现只是观察，定价版本才是金融真相。</strong>名称后缀、manifest 的 VERIFIED_FREE 分类都不产生价格；只有 ACTIVE 定价版本为零才是零价格。升级（Promote）是明确的人类治理动作。
      </div>
      {problem && <div role="alert"><Alert type="error" showIcon closable onClose={() => setProblem(null)} message="操作失败" description={(problem as { detail?: string }).detail ?? '请稍后重试。'} /></div>}
      <section className="v3-section" aria-label="连接选择">
        <div className="v3-filters">
          <label>连接
            <Select value={activeId ?? undefined} onChange={setConnectionId} aria-label="模型连接" size="small" style={{ minWidth: 220 }} placeholder="选择连接"
              options={connections.map((c) => ({ value: c.id, label: `连接 #${c.id} · v${c.version} · ${c.status}` }))} />
          </label>
          {!preview && canManage && activeId !== null && (
            <span className="v3-govern-actions">
              <Button size="small" disabled={working !== null} onClick={refresh}>{working === 'refresh' ? '发现中…' : '实时发现'}</Button>
            </span>
          )}
        </div>
        {!preview && canManage && activeId !== null && (
          <div className="v3-filters" style={{ marginTop: 10 }}>
            <label>手工登记模型名<Input value={manualName} onChange={(e) => setManualName(e.target.value)} aria-label="手工登记模型名" placeholder="gpt-5-class" style={{ minWidth: 220 }} /></label>
            <span className="v3-govern-actions"><Button size="small" disabled={manualName.trim() === '' || working !== null} onClick={registerManual}>登记</Button></span>
          </div>
        )}
      </section>
      {loadProblem && <div role="alert"><Alert type="error" showIcon message={loadProblem.title ?? '加载失败'} description={loadProblem.detail ?? '请稍后重试。'} /></div>}
      {isLoading && <div className="v3-skeleton" role="status" aria-label="正在加载模型目录"><div className="v3-note" style={{ minHeight: 140 }} /></div>}
      {!isLoading && !loadProblem && activeId === null && <div style={{ marginTop: 20 }}><Empty description="暂无连接，先去 Gallery 新建连接配置。" /></div>}
      {!isLoading && !loadProblem && activeId !== null && rows.length === 0 && <div style={{ marginTop: 20 }}><Empty description="该连接暂无模型观察：执行实时发现或手工登记。" /></div>}
      {promotion && (
        <div className="v3-note" role="status" style={{ marginTop: 12 }}>
          已升级为组织模型：逻辑 #{promotion.logicalModelId} · 服务商模型 #{promotion.providerModelId} · 定价就绪 {promotion.pricingReady ? '是' : '否'} · 路由就绪 {promotion.routingReady ? '是' : '否'}
        </div>
      )}
      {rows.length > 0 && (
        <ul className="v3-list" style={{ marginTop: 16 }}>
          {rows.map((row) => {
            const cap = caps[row.id]
            const chat = cap?.capabilities['CHAT_COMPLETIONS']
            return (
              <li key={row.id} className="v3-model-row">
                <div className="v3-row-main">
                  <p className="v3-row-title">{row.providerModelName} <span className="v3-row-sub">· {row.source}</span></p>
                  <div className="v3-model-states">
                    <span>可用性 <AvailabilityPill availability={row.availability} /></span>
                    <span>协议声明 <span className="v3-pill v3-pill-neutral">{protocolLabel(row.protocolCode)}</span></span>
                    <span>探针 <ProbePill status={row.lastProbeStatus} /></span>
                    <span>价格分类 <span className="v3-pill v3-pill-neutral">{row.pricingClassification || '未知'}</span></span>
                  </div>
                  {cap && <p className="v3-row-detail">实测能力：{Object.entries(cap.capabilities).map(([k, v]) => `${k}=${v}`).join(' · ') || '无'}</p>}
                  <p className="v3-row-detail">最近发现 {formatDateTime(row.lastSeenAt)}{row.lastProbedAt ? ` · 最近探测 ${formatDateTime(row.lastProbedAt)}${row.lastProbeErrorCode ? ` · ${row.lastProbeErrorCode}` : ''}` : ''}</p>
                </div>
                {!preview && canManage && (
                  <div className="v3-model-actions">
                    <button type="button" className="v3-link-button" disabled={working !== null} onClick={() => probeRow(row)}>{working === `probe-${row.id}` ? '探测中…' : '探针'}</button>
                    <button type="button" className="v3-link-button" disabled={working !== null || (chat !== 'VERIFIED' && row.protocolCode !== 'CHAT_COMPLETIONS')} onClick={() => promoteRow(row)} title="仅在可用且聊天兼容时可升级">{working === `promote-${row.id}` ? '升级中…' : '升级'}</button>
                  </div>
                )}
              </li>
            )
          })}
        </ul>
      )}
    </main>
  )
}
