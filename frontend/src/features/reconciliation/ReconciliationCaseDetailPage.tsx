import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Card, Col, Descriptions, Divider, Form, Input, Modal, Row, Skeleton, Space, Statistic, Tag, Typography } from 'antd'
import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { problemDetail, problemTitle, toProblemDetail } from '../../api/problem'
import { formatEventDateTime } from '../../lib/dateTime'
import { formatMoney } from '../../lib/money'
import { useAuth } from '../auth/AuthSessionProvider'
import { hasPermission } from '../settings/permissions'
import { reconciliationApi } from './api/reconciliationApi'
import { reconciliationKeys } from './api/reconciliationKeys'
import { createIdempotencyKey, formatReconciliationCaseStatus, formatReconciliationCaseType, reconciliationCaseTagColor } from './presentation'
import type { ReconciliationCaseResponse } from './types'

function CaseError({ error }: { error: unknown }) {
  const problem = toProblemDetail(error)
  return <Alert type="error" showIcon title="案例操作未完成" description={problemDetail(problem) ?? problemTitle(problem)} />
}

export function ReconciliationCaseDetailPage() {
  const { caseId = '' } = useParams<{ caseId: string }>()
  const navigate = useNavigate()
  const auth = useAuth()
  const queryClient = useQueryClient()
  const canResolve = hasPermission(auth.user?.permissions, 'RECONCILIATION_RESOLVE')
  const [resolveOpen, setResolveOpen] = useState(false)
  const [reasonCode, setReasonCode] = useState('')
  const [resolutionNote, setResolutionNote] = useState('')

  const detail = useQuery({ queryKey: reconciliationKeys.case(caseId), queryFn: () => reconciliationApi.getCase(caseId), enabled: caseId.length > 0 })
  const refresh = (updated: ReconciliationCaseResponse) => {
    void queryClient.invalidateQueries({ queryKey: reconciliationKeys.case(updated.id) })
    void queryClient.invalidateQueries({ queryKey: ['reconciliation', 'cases'] })
  }
  const investigate = useMutation({ mutationFn: () => reconciliationApi.investigateCase(caseId, createIdempotencyKey()), retry: false, onSuccess: refresh })
  const returnOpen = useMutation({ mutationFn: () => reconciliationApi.returnCaseToOpen(caseId, createIdempotencyKey()), retry: false, onSuccess: refresh })
  const resolve = useMutation({
    mutationFn: () => reconciliationApi.resolveCase(caseId, { reasonCode: reasonCode.trim(), resolutionNote: resolutionNote.trim() }, createIdempotencyKey()),
    retry: false,
    onSuccess: (updated) => { refresh(updated); setResolveOpen(false); setReasonCode(''); setResolutionNote('') },
  })

  if (detail.isLoading) return <main className="settings-page m6-page"><Skeleton active paragraph={{ rows: 8 }} /></main>
  if (detail.error || !detail.data) return <main className="settings-page m6-page"><CaseError error={detail.error ?? new Error('missing case')} /></main>
  const data = detail.data
  const actionError = investigate.error ?? returnOpen.error ?? resolve.error
  const busy = investigate.isPending || returnOpen.isPending || resolve.isPending

  return (
    <main className="settings-page m6-page">
      <header className="page-header m6-page-header">
        <div>
          <Button type="link" className="m6-back-link" onClick={() => navigate(`/reconciliation/${data.reconciliationRunId}`)}>← 返回运行详情</Button>
          <Typography.Text className="m6-eyebrow">财务 / 对账案例</Typography.Text>
          <h1>对账案例详情</h1>
          <Typography.Text type="secondary">案例 #{data.id} · 运行 #{data.reconciliationRunId}</Typography.Text>
        </div>
        {canResolve && (
          <Space wrap>
            {data.status === 'OPEN' && <Button type="primary" loading={investigate.isPending} disabled={busy} onClick={() => investigate.mutate()}>开始调查</Button>}
            {data.status === 'INVESTIGATING' && <Button loading={returnOpen.isPending} disabled={busy} onClick={() => returnOpen.mutate()}>退回待处理</Button>}
            {data.status === 'INVESTIGATING' && <Button type="primary" loading={resolve.isPending} disabled={busy} onClick={() => setResolveOpen(true)}>标记已解决</Button>}
          </Space>
        )}
      </header>
      {actionError && <CaseError error={actionError} />}

      <Row gutter={[16, 16]} className="m6-summary-grid">
        <Col xs={24} sm={8}><Card className="m6-stat-card"><Statistic title="案例状态" value={formatReconciliationCaseStatus(data.status)} /></Card></Col>
        <Col xs={24} sm={8}><Card className="m6-stat-card"><Statistic title="差异金额" value={formatMoney(data.differenceAmount, data.currency)} /></Card></Col>
        <Col xs={24} sm={8}><Card className="m6-stat-card"><Statistic title="数据行数" value={`${data.externalRowCount} / ${data.internalRowCount}`} suffix="外部 / 内部" /></Card></Col>
      </Row>

      <Card className="m6-section-card" title="金额核对">
        <Descriptions column={{ xs: 1, sm: 2, lg: 3 }} size="small">
          <Descriptions.Item label="状态"><Tag color={reconciliationCaseTagColor(data.status)}>{formatReconciliationCaseStatus(data.status)}</Tag></Descriptions.Item>
          <Descriptions.Item label="差异类型">{formatReconciliationCaseType(data.caseType)}</Descriptions.Item>
          <Descriptions.Item label="供应商账号">{data.providerAccountId}</Descriptions.Item>
          <Descriptions.Item label="币种">{data.currency}</Descriptions.Item>
          <Descriptions.Item label="外部金额">{formatMoney(data.externalAmount, data.currency)}</Descriptions.Item>
          <Descriptions.Item label="内部金额">{formatMoney(data.internalAmount, data.currency)}</Descriptions.Item>
          <Descriptions.Item label="差异金额">{formatMoney(data.differenceAmount, data.currency)}</Descriptions.Item>
          <Descriptions.Item label="外部记录数">{data.externalRowCount}</Descriptions.Item>
          <Descriptions.Item label="内部记录数">{data.internalRowCount}</Descriptions.Item>
        </Descriptions>
      </Card>
      <Card className="m6-section-card" title="处理记录">
        <Descriptions column={{ xs: 1, sm: 2 }} size="small">
          <Descriptions.Item label="创建时间">{formatEventDateTime(data.createdAt)}</Descriptions.Item>
          <Descriptions.Item label="更新时间">{formatEventDateTime(data.updatedAt)}</Descriptions.Item>
          <Descriptions.Item label="处理原因">{data.reasonCode ?? '—'}</Descriptions.Item>
          <Descriptions.Item label="解决时间">{formatEventDateTime(data.resolvedAt)}</Descriptions.Item>
        </Descriptions>
        <Divider />
        <Typography.Text type="secondary">解决说明</Typography.Text>
        <Typography.Paragraph className="m6-note-block">{data.resolutionNote ?? '尚未填写解决说明。'}</Typography.Paragraph>
      </Card>

      <Modal
        open={resolveOpen}
        title="解决对账案例"
        okText={resolve.isPending ? '正在提交…' : '确认解决'}
        okButtonProps={{ disabled: !reasonCode.trim() || !resolutionNote.trim() || resolve.isPending }}
        onOk={() => resolve.mutate()}
        onCancel={() => setResolveOpen(false)}
      >
        <Form layout="vertical">
          <Form.Item label="处理原因" required>
            <Input value={reasonCode} maxLength={100} placeholder="请输入处理原因" onChange={(event) => setReasonCode(event.target.value)} />
          </Form.Item>
          <Form.Item label="解决说明" required>
            <Input.TextArea value={resolutionNote} maxLength={2000} rows={5} placeholder="请说明本次案例的处理结论" onChange={(event) => setResolutionNote(event.target.value)} />
          </Form.Item>
        </Form>
      </Modal>
    </main>
  )
}
