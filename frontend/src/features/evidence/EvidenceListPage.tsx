import { Button, Table, message } from 'antd'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthSessionProvider'
import { hasPermission } from '../settings/permissions'
import { evidenceApi } from './api/evidenceApi'
import { evidenceKeys } from './api/evidenceKeys'
import type { EvidenceSummary } from './api/evidenceTypes'
import { ProviderImportUploadModal, type ProviderImportUploadResult } from '../imports/upload/ProviderImportUploadModal'

const PAGE_SIZE = 50

export function EvidenceListPage() {
  const auth = useAuth()
  const navigate = useNavigate()
  const [page, setPage] = useState(0)
  const [uploadOpen, setUploadOpen] = useState(false)

  const canUpload = hasPermission(auth.user?.permissions, 'EVIDENCE_UPLOAD_PROVIDER')
    && hasPermission(auth.user?.permissions, 'PROVIDER_ACCOUNT_READ')

  const list = useQuery({
    queryKey: evidenceKeys.list(page, PAGE_SIZE),
    queryFn: () => evidenceApi.listEvidence(page, PAGE_SIZE),
  })

  const handleUploaded = (result: ProviderImportUploadResult) => {
    setUploadOpen(false)
    if (result.duplicateBatch || result.duplicateEvidence) {
      message.info('已复用现有资源：' + (result.duplicateBatch ? '已存在同名导入' : '已复用已有证据文件'))
    }
    navigate(`/imports/${result.importBatchId}`)
  }

  return (
    <div className="evidence-page">
      <header className="page-header">
        <h1>证据</h1>
        {canUpload && (
          <Button type="primary" onClick={() => setUploadOpen(true)}>上传 Provider 账单</Button>
        )}
      </header>
      <Table<EvidenceSummary>
        rowKey="id"
        loading={list.isLoading}
        dataSource={list.data?.items ?? []}
        pagination={{
          current: page + 1,
          pageSize: PAGE_SIZE,
          total: list.data?.totalElements ?? 0,
          showSizeChanger: false,
          onChange: (nextPage) => setPage(nextPage - 1),
        }}
        onRow={(row) => ({ onClick: () => navigate(`/evidence/${row.id}`) })}
        columns={[
          { title: '文件名', dataIndex: 'originalFilename' },
          { title: '类型', dataIndex: 'mediaType' },
          { title: '大小 (bytes)', dataIndex: 'sizeBytes' },
          { title: '状态', dataIndex: 'storageStatus' },
          { title: '上传时间', dataIndex: 'createdAt' },
        ]}
      />
      <ProviderImportUploadModal
        open={uploadOpen}
        onClose={() => setUploadOpen(false)}
        onUploaded={handleUploaded}
      />
    </div>
  )
}
