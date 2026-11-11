import { useQuery } from '@tanstack/react-query'
import { Alert, Empty, Select } from 'antd'
import { useState } from 'react'
import { toProblemDetail } from '../../api/problem'
import { useAuth } from '../auth/AuthSessionProvider'
import { hasPermission } from '../auth/permissions'
import { intelligenceApi } from './api/intelligenceApi'
import { intelligenceKeys } from './api/intelligenceKeys'
import type { BudgetRisk, CostAnomaly, CostForecast, IntelligenceSummary, SavingRecommendation } from './api/intelligenceTypes'
import { formatDateTime } from './format'
import { AdvisorBand, AnomalySection, AnswerStrip, ExposureSection, OverviewSkeleton, SavingsSection } from './OverviewSections'
import { TopDriversSection } from './TopDriversSection'
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

/** 智能总览运行状态；未知状态回退展示原始枚举。 */
const RUN_STATUS_LABEL: Record<string, string> = {
  COMPLETED: '已完成',
  FAILED: '失败',
  STALE: '已过期',
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
  const hasSnapshot = summary?.runId !== null && summary?.runId !== undefined
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
        <p className="v3-lede">先看四个关键结论，再查看详细证据。</p>
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
          {summary && <div><dt>运行</dt><dd>{summary.runId === null || summary.runId === undefined ? '暂无' : `#${summary.runId} · ${RUN_STATUS_LABEL[summary.status] ?? summary.status}`}</dd></div>}
          {preview && <div><dt>生成于</dt><dd>{formatDateTime(preview.generatedAt)}</dd></div>}
          {orgForecast && <div><dt>观测至</dt><dd>{orgForecast.observedThrough}</dd></div>}
        </dl>
      </header>
      {loadProblem && (
        <div style={{ marginTop: 16 }} role="alert">
          <Alert type="error" showIcon title={loadProblem.title ?? '加载失败'} description={loadProblem.detail ?? '请稍后重试。'} />
        </div>
      )}
      {isLoading && <OverviewSkeleton />}
      {!isLoading && !loadError && !hasSnapshot && (
        <div style={{ marginTop: 20 }}>
          <Empty description="暂无智能分析结果，调度器完成首次运行后自动呈现" />
        </div>
      )}
      {!isLoading && !loadError && hasSnapshot && summary && (
        <>
          {summary.status !== 'COMPLETED' && (
            <div style={{ marginTop: 16 }}>
              <Alert
                type="warning"
                showIcon
                message="数据为上次运行结果，正在自动更新。"
              />
            </div>
          )}
          <AnswerStrip summary={summary} anomalies={anomalies} forecast={orgForecast} recommendations={recommendations} currency={activeCurrency} />
          <AnomalySection anomalies={anomalies} currency={activeCurrency} />
          <TopDriversSection anomalies={anomalies} currency={activeCurrency} />
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
