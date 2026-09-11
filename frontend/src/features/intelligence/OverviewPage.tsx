import { useQuery } from '@tanstack/react-query'
import { Alert, Empty, Select } from 'antd'
import { useState } from 'react'
import { toProblemDetail } from '../../api/problem'
import { useAuth } from '../auth/AuthSessionProvider'
import { hasPermission } from '../settings/permissions'
import { intelligenceApi } from './api/intelligenceApi'
import { intelligenceKeys } from './api/intelligenceKeys'
import type { BudgetRisk, CostAnomaly, CostForecast, IntelligenceSummary, SavingRecommendation } from './api/intelligenceTypes'
import { formatDateTime } from './format'
import { AdvisorBand, AnomalySection, AnswerStrip, ExposureSection, OverviewSkeleton, SavingsSection } from './OverviewSections'
import './v3-tokens.css'

export interface OverviewPreviewData {
  currency: string
  generatedAt: string
  summary: IntelligenceSummary
  anomalies: CostAnomaly[]
  forecasts: CostForecast[]
  budgetRisk: BudgetRisk | null
  recommendations: SavingRecommendation[]
}

export interface OverviewAuthOverride {
  organizationId?: string
  canReadBudget: boolean
}

interface OverviewPageProps {
  preview?: OverviewPreviewData
  authOverride?: OverviewAuthOverride
}

/** preview + authOverride are DEV-benchmark-only; production routes pass neither. */
export function OverviewPage(props: OverviewPageProps) {
  const auth = useAuth()
  const [currency, setCurrency] = useState('USD')
  const preview = props.preview
  const activeCurrency = preview?.currency ?? currency
  const orgId = props.authOverride?.organizationId ?? auth.user?.organizationId
  const canReadBudget = props.authOverride?.canReadBudget ?? hasPermission(auth.user?.permissions, 'BUDGET_READ')

  const summaryQuery = useQuery({
    queryKey: intelligenceKeys.summary(activeCurrency),
    queryFn: () => intelligenceApi.summary(activeCurrency),
    enabled: !preview,
    staleTime: 30_000,
  })
  const anomaliesQuery = useQuery({
    queryKey: intelligenceKeys.anomalies(activeCurrency),
    queryFn: () => intelligenceApi.anomalies(activeCurrency),
    enabled: !preview,
    staleTime: 30_000,
  })
  const forecastsQuery = useQuery({
    queryKey: intelligenceKeys.forecasts(activeCurrency),
    queryFn: () => intelligenceApi.forecasts(activeCurrency),
    enabled: !preview,
    staleTime: 30_000,
  })
  const recommendationsQuery = useQuery({
    queryKey: intelligenceKeys.recommendations(activeCurrency),
    queryFn: () => intelligenceApi.recommendations(activeCurrency),
    enabled: !preview,
    staleTime: 30_000,
  })

  const summary = preview?.summary ?? summaryQuery.data
  const anomalies = preview?.anomalies ?? anomaliesQuery.data ?? []
  const forecasts = preview?.forecasts ?? forecastsQuery.data ?? []
  const recommendations = preview?.recommendations ?? recommendationsQuery.data ?? []
  const orgForecast = forecasts.find((f) => f.scopeType === 'ORGANIZATION') ?? forecasts[0]

  const isLoading = !preview && (summaryQuery.isLoading || anomaliesQuery.isLoading || forecastsQuery.isLoading || recommendationsQuery.isLoading)
  const loadError = !preview
    ? summaryQuery.error ?? anomaliesQuery.error ?? forecastsQuery.error ?? recommendationsQuery.error ?? null
    : null
  const loadProblem = loadError ? (toProblemDetail(loadError) as { title?: string; detail?: string }) : null

  return (
    <main className="v3-page" aria-busy={isLoading}>
      <header>
        <span className="v3-eyebrow">Cost Intelligence · 成本智能</span>
        <h1>成本智能总览</h1>
        <p className="v3-lede">所有金额来自后端确定性计算，本页只做呈现与解释。先回答四个问题，再展开证据。</p>
        <dl className="v3-meta">
          <div><dt>币种</dt><dd>{preview ? activeCurrency : (
            <Select
              value={activeCurrency}
              onChange={setCurrency}
              aria-label="结算币种"
              size="small"
              options={['USD', 'CNY', 'EUR', 'GBP'].map((c) => ({ value: c, label: c }))}
            />
          )}</dd></div>
          {summary && <div><dt>运行</dt><dd>{summary.runId === null || summary.runId === undefined ? '暂无' : `#${summary.runId} · ${summary.status}`}</dd></div>}
          {preview && <div><dt>生成于</dt><dd>{formatDateTime(preview.generatedAt)}</dd></div>}
          {orgForecast && <div><dt>观测至</dt><dd>{orgForecast.observedThrough}</dd></div>}
        </dl>
      </header>
      {loadProblem && (
        <div style={{ marginTop: 16 }} role="alert">
          <Alert type="error" showIcon message={loadProblem.title ?? '加载失败'} description={loadProblem.detail ?? '请稍后重试。'} />
        </div>
      )}
      {isLoading && <OverviewSkeleton />}
      {!isLoading && !loadError && !summary && (
        <div style={{ marginTop: 20 }}>
          <Empty description="暂无智能分析结果，调度器完成首次运行后自动呈现" />
        </div>
      )}
      {!isLoading && !loadError && summary && (
        <>
          {summary.status !== 'COMPLETED' && (
            <div style={{ marginTop: 16 }}>
              <Alert
                type="warning"
                showIcon
                message="快照状态"
                description="当前显示为上次成功运行的快照（STALE），调度器将自动更新。确定性智能仍可用。"
              />
            </div>
          )}
          <AnswerStrip summary={summary} anomalies={anomalies} forecast={orgForecast} recommendations={recommendations} currency={activeCurrency} />
          <AnomalySection anomalies={anomalies} currency={activeCurrency} />
          <ExposureSection
            previewRisk={preview?.budgetRisk ?? undefined}
            previewMode={Boolean(preview)}
            canReadBudget={canReadBudget}
            orgId={orgId}
            currency={activeCurrency}
            forecast={orgForecast}
          />
          <SavingsSection recommendations={recommendations} currency={activeCurrency} />
          <AdvisorBand />
        </>
      )}
    </main>
  )
}
