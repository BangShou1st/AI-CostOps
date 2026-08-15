import { Button, Select, Table, message } from 'antd'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthSessionProvider'
import { hasPermission } from '../settings/permissions'
import { settingsApi } from '../settings/api/settingsApi'
import { settingsKeys } from '../settings/api/settingsKeys'
import { importKeys } from './api/importKeys'
import { importsApi } from './api/importsApi'
import type { ImportBatchStatus, ImportSummary } from './api/importTypes'
import { ProviderImportUploadModal, type ProviderImportUploadResult } from './upload/ProviderImportUploadModal'

const PAGE_SIZE = 50

const STATUS_OPTIONS: ImportBatchStatus[] = ['PENDING', 'PROCESSING', 'PARSED', 'FAILED', 'CANCELED']

export function ImportListPage() {
  const auth = useAuth()
  const navigate = useNavigate()
  const [page, setPage] = useState(0)
  const [status, setStatus] = useState<ImportBatchStatus | undefined>()
  const [providerAccountId, setProviderAccountId] = useState<string | undefined>()
  const [uploadOpen, setUploadOpen] = useState(false)

  const canUpload = hasPermission(auth.user?.permissions, 'EVIDENCE_UPLOAD_PROVIDER')
    && hasPermission(auth.user?.permissions, 'PROVIDER_ACCOUNT_READ')
  // Amendment 4: the provider-account filter options query must not mount
  // without PROVIDER_ACCOUNT_READ, even though the import list itself works.
  const canFilterByAccount = hasPermission(auth.user?.permissions, 'PROVIDER_ACCOUNT_READ')

  const list = useQuery({
    queryKey: importKeys.list({ page, size: PAGE_SIZE, status, providerAccountId }),
    queryFn: () => importsApi.listImports({ page, size: PAGE_SIZE, status, providerAccountId }),
  })

  const accounts = useQuery({
    queryKey: settingsKeys.providerAccountsAll(),
    queryFn: async () => {
      const result = await settingsApi.listProviderAccounts(0, 200)
      return result.items.filter((account) => account.status === 'ACTIVE')
    },
    enabled: canFilterByAccount,
  })

  const handleUploaded = (result: ProviderImportUploadResult) => {
    setUploadOpen(false)
    if (result.duplicateBatch || result.duplicateEvidence) {
      message.info('已复用现有资源：' + (result.duplicateBatch ? '已存在同名导入' : '已复用已有证据文件'))
    }
    navigate(`/imports/${result.importBatchId}`)
  }

  return (
    <div className="imports-page">
      <header className="page-header">
        <h1>导入</h1>
        {canUpload && (
          <Button type="primary" onClick={() => setUploadOpen(true)}>上传 Provider 账单</Button>
        )}
      </header>
      <div className="filters">
        <Select
          allowClear
          placeholder="状态"
          aria-label="状态"
          value={status}
          onChange={(value) => {
            setStatus(value)
            setPage(0)
          }}
          options={STATUS_OPTIONS.map((value) => ({ value, label: value }))}
          style={{ width: 180 }}
        />
        {canFilterByAccount && (
          <Select
            allowClear
            placeholder="Provider 账号"
            aria-label="Provider 账号"
            value={providerAccountId}
            onChange={(value) => {
              setProviderAccountId(value)
              setPage(0)
            }}
            options={(accounts.data ?? []).map((account) => ({
              value: account.id,
              label: account.displayName,
            }))}
            loading={accounts.isLoading}
            style={{ width: 220 }}
          />
        )}
      </div>
      <Table<ImportSummary>
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
        onRow={(row) => ({ onClick: () => navigate(`/imports/${row.id}`) })}
        columns={[
          { title: 'ID', dataIndex: 'id' },
          { title: '证据文件', render: (_, row) => row.evidence.originalFilename },
          { title: 'Provider 账号', render: (_, row) => row.providerAccount.displayName },
          { title: '来源类型', render: (_, row) => row.sourceType ?? '—' },
          { title: '状态', dataIndex: 'status' },
          { title: '创建时间', dataIndex: 'createdAt' },
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
