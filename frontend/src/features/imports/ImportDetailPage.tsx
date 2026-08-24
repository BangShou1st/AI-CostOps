import { Alert, Button, Descriptions, Popconfirm, Tabs } from 'antd'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { useParams } from 'react-router-dom'
import { problemDetail as presentProblemDetail, problemTitle, toProblemDetail } from '../../api/problem'
import { useAuth } from '../auth/AuthSessionProvider'
import { hasPermission } from '../settings/permissions'
import { AttemptReview } from './AttemptReview'
import { importKeys } from './api/importKeys'
import { importsApi } from './api/importsApi'
import type { ImportSummary } from './api/importTypes'
import { formatBusinessDate, formatEventDateTime } from '../../lib/dateTime'
import { createImportIdempotencyKey, formatImportAttemptStatus, formatImportSourceType, formatImportStatus } from './presentation'

/** Only the lightweight Import detail polls; Issues/Raw Records never do. */
const ACTIVE_POLL_INTERVAL_MS = 3000

const ACTIVE_STATUSES = new Set(['PENDING', 'PROCESSING'])
const TERMINAL_STATUSES = new Set(['PARSED', 'READY_FOR_REVIEW', 'CONFIRMED', 'FAILED', 'CANCELED'])

export function ImportDetailPage({ importId: propImportId }: { importId?: string } = {}) {
  const auth = useAuth()
  const { id } = useParams()
  const importId = propImportId ?? id ?? ''
  const queryClient = useQueryClient()
  const [commandError, setCommandError] = useState<string | null>(null)
  const previousStatus = useRef<string | null>(null)

  const canRetry = hasPermission(auth.user?.permissions, 'IMPORT_RETRY')
  const canCancel = hasPermission(auth.user?.permissions, 'IMPORT_CANCEL')
  const canConfirm = hasPermission(auth.user?.permissions, 'IMPORT_CONFIRM')

  const detail = useQuery({
    queryKey: importKeys.detail(importId),
    queryFn: () => importsApi.getImport(importId),
    enabled: importId.length > 0,
    refetchInterval: (query) => {
      const status = query.state.data?.status
      return status === 'PENDING' || status === 'PROCESSING' ? ACTIVE_POLL_INTERVAL_MS : false
    },
  })

  /** Invalidates every Attempt/Issue/RawRecord page under the import. */
  const invalidateLineage = () => {
    void queryClient.invalidateQueries({ queryKey: ['imports', importId, 'attempts'] })
    void queryClient.invalidateQueries({ queryKey: importKeys.lists() })
  }

  const invalidateEvidenceImports = (evidenceId: string | undefined) => {
    if (evidenceId) {
      void queryClient.invalidateQueries({ queryKey: ['evidence', evidenceId, 'imports'] })
    }
  }

  // A real active -> terminal transition must refresh every dependent cache:
  // attempt lineage, current issues/raw records, evidence imports, import list.
  const status = detail.data?.status ?? null
  useEffect(() => {
    const previous = previousStatus.current
    previousStatus.current = status
    if (previous !== null && ACTIVE_STATUSES.has(previous) && status !== null && TERMINAL_STATUSES.has(status)) {
      invalidateLineage()
      invalidateEvidenceImports(detail.data?.evidence.id)
    }
  }, [status])

  const retryCommand = useMutation({
    mutationFn: (key: string) => importsApi.retry(importId, key),
    retry: false,
    onSuccess: (result) => {
      setCommandError(null)
      // The backend response is authoritative: Retry -> PENDING restarts
      // polling immediately, before any dependent refetch.
      queryClient.setQueryData(importKeys.detail(importId), result)
      invalidateLineage()
      invalidateEvidenceImports(result.evidence.id)
    },
    onError: (error: unknown) => {
      setCommandError(presentProblemDetail(toProblemDetail(error)) ?? problemTitle(toProblemDetail(error)))
      // State changed under us: refresh current state, never auto-replay.
      void queryClient.invalidateQueries({ queryKey: importKeys.detail(importId) })
    },
  })

  const cancelCommand = useMutation({
    mutationFn: (key: string) => importsApi.cancel(importId, key),
    retry: false,
    onSuccess: (result) => {
      setCommandError(null)
      // Cancel -> CANCELED stops polling immediately via the cache update.
      queryClient.setQueryData(importKeys.detail(importId), result)
      invalidateLineage()
      invalidateEvidenceImports(result.evidence.id)
    },
    onError: (error: unknown) => {
      setCommandError(presentProblemDetail(toProblemDetail(error)) ?? problemTitle(toProblemDetail(error)))
      void queryClient.invalidateQueries({ queryKey: importKeys.detail(importId) })
    },
  })

  const confirmCommand = useMutation({
    mutationFn: (key: string) => importsApi.confirm(importId, key),
    retry: false,
    onSuccess: (result) => {
      setCommandError(null)
      queryClient.setQueryData(importKeys.detail(importId), result)
      invalidateLineage()
      invalidateEvidenceImports(result.evidence.id)
    },
    onError: (error: unknown) => {
      setCommandError(presentProblemDetail(toProblemDetail(error)) ?? problemTitle(toProblemDetail(error)))
      void queryClient.invalidateQueries({ queryKey: importKeys.detail(importId) })
    },
  })

  if (detail.isError) {
    const problem = toProblemDetail(detail.error)
    return (
      <div className="imports-page">
        <Alert
          type="error"
          showIcon
          title="加载失败"
          description={presentProblemDetail(problem) ?? problemTitle(problem)}
        />
        <Button onClick={() => void queryClient.invalidateQueries({ queryKey: importKeys.detail(importId) })}>
          重试
        </Button>
      </div>
    )
  }

  const data = detail.data
  if (detail.isLoading || !data) {
    return <div className="imports-page" role="status">正在加载导入…</div>
  }

  const handleRetry = () => {
    retryCommand.mutate(createImportIdempotencyKey())
  }

  const handleCancel = () => {
    cancelCommand.mutate(createImportIdempotencyKey())
  }

  const failed = data.status === 'FAILED'
  const cancelLabel = failed ? '放弃导入' : '取消导入'
  const cancelConfirmation = failed
    ? '确定放弃这个失败导入吗？放弃后将不再阻塞账期关闭。'
    : '确定取消这个导入吗？'
  const cancelConfirmLabel = failed ? '确认放弃' : '确认取消'

  const confirmable = canConfirm
    && data.status === 'READY_FOR_REVIEW'
    && data.latestAttempt?.status === 'SUCCEEDED'
    && data.latestAttempt.errorCount === 0

  const handleConfirm = () => {
    confirmCommand.mutate(createImportIdempotencyKey())
  }

  const tabs = [
    { key: 'overview', label: '概览', children: <Overview data={data} /> },
    { key: 'attempts', label: '尝试', children: <AttemptReview importId={importId} /> },
  ]

  return (
    <div className="imports-page">
      <header className="page-header">
        <h1>导入 {data.id}</h1>
        <div className="page-actions">
          {canRetry && data.retryable && (
            <Button loading={retryCommand.isPending} onClick={handleRetry}>重试</Button>
          )}
          {canCancel && data.cancelable && (
            <Popconfirm
              title={cancelConfirmation}
              okText={cancelConfirmLabel}
              cancelText="返回"
              onConfirm={handleCancel}
            >
              <Button danger loading={cancelCommand.isPending}>{cancelLabel}</Button>
            </Popconfirm>
          )}
          {confirmable && (
            <Button type="primary" loading={confirmCommand.isPending} onClick={handleConfirm}>确认导入</Button>
          )}
        </div>
      </header>
      {commandError && <Alert type="error" showIcon title="操作失败" description={commandError} />}
      <Tabs items={tabs} />
    </div>
  )
}

function Overview({ data }: { data: ImportSummary }) {
  return (
    <Descriptions column={1} bordered size="small">
      <Descriptions.Item label="状态">{formatImportStatus(data.status)}</Descriptions.Item>
      <Descriptions.Item label="证据文件">{data.evidence.originalFilename}</Descriptions.Item>
      <Descriptions.Item label="供应商账号">{data.providerAccount.displayName}</Descriptions.Item>
      <Descriptions.Item label="期望供应商">{data.expectedProviderCode}</Descriptions.Item>
      <Descriptions.Item label="来源类型">{formatImportSourceType(data.sourceType)}</Descriptions.Item>
      <Descriptions.Item label="解析器版本">{data.parserVersion}</Descriptions.Item>
      <Descriptions.Item label="周期开始">{formatBusinessDate(data.periodStart)}</Descriptions.Item>
      <Descriptions.Item label="周期结束">{formatBusinessDate(data.periodEnd)}</Descriptions.Item>
      <Descriptions.Item label="最近尝试">
        {data.latestAttempt
          ? `#${data.latestAttempt.attemptNo} ${formatImportAttemptStatus(data.latestAttempt.status)}`
          : '—'}
      </Descriptions.Item>
      <Descriptions.Item label="创建时间">{formatEventDateTime(data.createdAt)}</Descriptions.Item>
      <Descriptions.Item label="更新时间">{formatEventDateTime(data.updatedAt)}</Descriptions.Item>
    </Descriptions>
  )
}
