import { Alert, Button, Descriptions, Table, message } from 'antd'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { apiClient } from '../auth/authApi'
import { useAuth } from '../auth/AuthSessionProvider'
import { hasPermission } from '../settings/permissions'
import { evidenceApi } from './api/evidenceApi'
import { importsApi } from '../imports/api/importsApi'
import { evidenceKeys } from './api/evidenceKeys'
import type { ImportSummary } from '../imports/api/importTypes'

const IMPORTS_PAGE_SIZE = 50

export function EvidenceDetailPage({ evidenceId: propEvidenceId }: { evidenceId?: string } = {}) {
  const auth = useAuth()
  const navigate = useNavigate()
  const { id } = useParams()
  const evidenceId = propEvidenceId ?? id ?? ''
  const [importsPage, setImportsPage] = useState(0)

  const canDownload = hasPermission(auth.user?.permissions, 'EVIDENCE_DOWNLOAD')
  const canReadImports = hasPermission(auth.user?.permissions, 'IMPORT_READ')

  const detail = useQuery({
    queryKey: evidenceKeys.detail(evidenceId),
    queryFn: () => evidenceApi.getEvidence(evidenceId),
    enabled: evidenceId.length > 0,
  })

  // The associated-imports child query must never start without IMPORT_READ.
  const imports = useQuery({
    queryKey: evidenceKeys.imports(evidenceId, importsPage, IMPORTS_PAGE_SIZE),
    queryFn: () => importsApi.listEvidenceImports(evidenceId, importsPage, IMPORTS_PAGE_SIZE),
    enabled: canReadImports,
  })

  const handleDownload = async () => {
    try {
      const response = await apiClient.get(`/evidence/${encodeURIComponent(evidenceId)}/download`, {
        responseType: 'blob',
      })
      const url = URL.createObjectURL(response.data as Blob)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = detail.data?.originalFilename ?? 'evidence.bin'
      anchor.click()
      URL.revokeObjectURL(url)
    } catch {
      message.error('下载失败，请稍后重试。')
    }
  }

  const evidence = detail.data
  if (detail.isError) {
    return (
      <div className="evidence-page">
        <Alert type="error" showIcon title="加载失败" description="证据详情暂时不可用。" />
      </div>
    )
  }
  if (detail.isLoading || !evidence) {
    return <div className="evidence-page" role="status">正在加载证据…</div>
  }

  return (
    <div className="evidence-page">
      <header className="page-header">
        <h1>证据详情</h1>
        {canDownload && <Button onClick={() => void handleDownload()}>下载</Button>}
      </header>
      <Descriptions column={1} bordered size="small">
        <Descriptions.Item label="文件名">{evidence.originalFilename}</Descriptions.Item>
        <Descriptions.Item label="类型">{evidence.mediaType ?? '未知'}</Descriptions.Item>
        <Descriptions.Item label="大小 (bytes)">{evidence.sizeBytes}</Descriptions.Item>
        <Descriptions.Item label="SHA-256">{evidence.sha256}</Descriptions.Item>
        <Descriptions.Item label="存储状态">{evidence.storageStatus}</Descriptions.Item>
        <Descriptions.Item label="上传人">{evidence.uploadedByMemberId}</Descriptions.Item>
        <Descriptions.Item label="创建时间">{evidence.createdAt}</Descriptions.Item>
        <Descriptions.Item label="更新时间">{evidence.updatedAt}</Descriptions.Item>
      </Descriptions>
      {canReadImports && (
        <section className="evidence-imports">
          <h2>关联导入</h2>
          <Table<ImportSummary>
            rowKey="id"
            loading={imports.isLoading}
            dataSource={imports.data?.items ?? []}
            pagination={{
              current: importsPage + 1,
              pageSize: IMPORTS_PAGE_SIZE,
              total: imports.data?.totalElements ?? 0,
              showSizeChanger: false,
              onChange: (nextPage) => setImportsPage(nextPage - 1),
            }}
            onRow={(row) => ({ onClick: () => navigate(`/imports/${row.id}`) })}
            columns={[
              { title: '导入 ID', dataIndex: 'id' },
              { title: 'Provider 账号', render: (_, row) => row.providerAccount.displayName },
              { title: '状态', dataIndex: 'status' },
            ]}
          />
        </section>
      )}
    </div>
  )
}
