import { useQueryClient } from '@tanstack/react-query'
import { useQuery } from '@tanstack/react-query'
import { Alert, Button, Input, Modal, Select, Table, Tag, Typography } from 'antd'
import type { TableProps } from 'antd'
import { useState } from 'react'
import { toProblemDetail, type ProblemDetail } from '../../../api/problem'
import { useAuth } from '../../auth/AuthSessionProvider'
import { settingsApi } from '../api/settingsApi'
import { settingsKeys } from '../api/settingsKeys'
import type { MasterDataStatus, ProviderAccount } from '../api/settingsTypes'
import { hasPermission } from '../permissions'
import { statusLabel } from '../presentation'
import { useAuthorizationMutation } from '../useAuthorizationMutation'
import { MASTER_DATA_STATUS_OPTIONS } from '../shared/LifecycleEditorModal'

const SECRET_KEY_PATTERN = /password|token|secret|apikey/

/** Normalizes a metadata key the same way the backend does before matching. */
export function normalizeMetadataKey(key: string): string {
  return key.toLowerCase().replace(/[^a-z0-9]/g, '')
}

export function isSecretMetadataKey(key: string): boolean {
  return SECRET_KEY_PATTERN.test(normalizeMetadataKey(key))
}

function containsSecretMetadataKey(value: unknown): boolean {
  if (Array.isArray(value)) return value.some((child) => containsSecretMetadataKey(child))
  if (value === null || typeof value !== 'object') return false
  return Object.entries(value).some(
    ([key, child]) => isSecretMetadataKey(key) || containsSecretMetadataKey(child),
  )
}

/** Parses a metadata JSON text into a plain object, or returns null when invalid. */
function parseMetadataObject(text: string): Record<string, unknown> | null {
  if (!text.trim()) return {}
  let parsed: unknown
  try {
    parsed = JSON.parse(text)
  } catch {
    return null
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) return null
  return parsed as Record<string, unknown>
}

export function ProviderAccountsPage() {
  const auth = useAuth()
  const queryClient = useQueryClient()
  const [page, setPage] = useState(0)
  const [editor, setEditor] = useState<{ mode: 'create' } | { mode: 'edit'; record: ProviderAccount } | null>(null)
  const [problem, setProblem] = useState<ProblemDetail | null>(null)

  const listQuery = useQuery({
    queryKey: settingsKeys.providerAccounts(page, 50),
    queryFn: () => settingsApi.listProviderAccounts(page, 50),
  })

  const canManage = hasPermission(auth.user?.permissions, 'PROVIDER_ACCOUNT_MANAGE')

  const saveMutation = useAuthorizationMutation({
    mutationFn: (input: {
      providerCode: string
      displayName: string
      externalAccountRef?: string
      status?: MasterDataStatus
      metadata?: Record<string, unknown>
    }) => {
      if (editor?.mode === 'edit') {
        const update: {
          displayName: string
          externalAccountRef?: string
          status?: MasterDataStatus
          metadata?: Record<string, unknown>
        } = {
          displayName: input.displayName,
          // An explicitly cleared field must be sent as "" so the backend
          // stores null instead of keeping the previous value.
          externalAccountRef: input.externalAccountRef,
          status: input.status,
        }
        // Untouched metadata stays out of the request so the backend keeps it
        // byte-for-byte instead of being rewritten through the edit form.
        if (input.metadata !== undefined) update.metadata = input.metadata
        return settingsApi.updateProviderAccount(editor.record.id, update)
      }
      return settingsApi.createProviderAccount({
        providerCode: input.providerCode,
        displayName: input.displayName,
        externalAccountRef: input.externalAccountRef || undefined,
        metadata: input.metadata ?? {},
      })
    },
    retry: false,
    onSuccess: () => {
      setProblem(null)
      setEditor(null)
      void queryClient.invalidateQueries({ queryKey: settingsKeys.providerAccountsAll() })
    },
    onError: (error) => setProblem(toProblemDetail(error)),
  })

  const columns: TableProps<ProviderAccount>['columns'] = [
    { title: '云服务商', dataIndex: 'providerCode', key: 'providerCode', width: 140 },
    { title: '显示名称', dataIndex: 'displayName', key: 'displayName' },
    { title: '外部账号引用', dataIndex: 'externalAccountRef', key: 'externalAccountRef', render: (value: string | null) => value ?? '—' },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 110,
      render: (status: string) => <Tag color={status === 'ACTIVE' ? 'green' : status === 'ARCHIVED' ? 'orange' : 'default'}>{statusLabel(status as MasterDataStatus)}</Tag>,
    },
    {
      title: '操作', key: 'actions', width: 100,
      render: (_, record) => canManage ? (
        <Button size="small" onClick={() => { setProblem(null); setEditor({ mode: 'edit', record }) }}>编辑</Button>
      ) : null,
    },
  ]

  return (
    <main className="settings-page">
      <div className="settings-toolbar">
        <div>
          <h1>云账号</h1>
          <Typography.Text type="secondary">管理组织的云服务商账号。</Typography.Text>
        </div>
        {canManage && <Button type="primary" onClick={() => { setProblem(null); setEditor({ mode: 'create' }) }}>创建云账号</Button>}
      </div>
      {listQuery.isLoading && <div role="status">正在加载云账号…</div>}
      {listQuery.isError && (
        <Alert type="error" role="alert" message={toProblemDetail(listQuery.error).detail || toProblemDetail(listQuery.error).title} showIcon />
      )}
      {listQuery.data && listQuery.data.items.length === 0 && <div className="settings-empty">该组织暂无云账号。</div>}
      {listQuery.data && listQuery.data.items.length > 0 && (
        <Table<ProviderAccount>
          rowKey="id"
          columns={columns}
          dataSource={listQuery.data.items}
          scroll={{ x: 780 }}
          pagination={{
            current: listQuery.data.page + 1,
            pageSize: listQuery.data.size,
            total: listQuery.data.totalElements,
            onChange: (current) => setPage(current - 1),
          }}
        />
      )}
      {editor && (
        <ProviderAccountEditorModal
          title={editor.mode === 'edit' ? `编辑云账号 ${editor.record.providerCode}` : '创建云账号'}
          submitting={saveMutation.isPending}
          error={problem}
          initial={editor.mode === 'edit' ? editor.record : undefined}
          onCancel={() => setEditor(null)}
          onSave={(input) => saveMutation.mutate(input)}
        />
      )}
    </main>
  )
}

function ProviderAccountEditorModal({ title, submitting, error, initial, onCancel, onSave }: {
  title: string
  submitting: boolean
  error: ProblemDetail | null
  initial?: ProviderAccount
  onCancel: () => void
  onSave: (input: {
    providerCode: string
    displayName: string
    externalAccountRef?: string
    status?: MasterDataStatus
    metadata?: Record<string, unknown>
  }) => void
}) {
  const editing = initial !== undefined
  const [providerCode, setProviderCode] = useState(initial?.providerCode ?? '')
  const [displayName, setDisplayName] = useState(initial?.displayName ?? '')
  const [externalAccountRef, setExternalAccountRef] = useState(initial?.externalAccountRef ?? '')
  const [status, setStatus] = useState<MasterDataStatus>(initial?.status ?? 'ACTIVE')
  const [metadataText, setMetadataText] = useState(() => JSON.stringify(initial?.metadata ?? {}, null, 2))
  const [metadataDirty, setMetadataDirty] = useState(false)
  const [metadataProblem, setMetadataProblem] = useState<string | null>(null)

  function changeMetadata(text: string) {
    setMetadataText(text)
    setMetadataDirty(true)
    if (!text.trim()) {
      setMetadataProblem(null)
      return
    }
    let parsed: unknown
    try {
      parsed = JSON.parse(text)
    } catch {
      setMetadataProblem('云账号元数据必须是有效的 JSON。')
      return
    }
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      setMetadataProblem('云账号元数据必须是 JSON 对象。')
    } else if (containsSecretMetadataKey(parsed)) {
      setMetadataProblem('元数据键名不得包含 password、token、secret 或 apikey 片段。')
    } else {
      setMetadataProblem(null)
    }
  }

  function submit() {
    // In edit mode an untouched metadata block is omitted so the backend keeps
    // the stored value; explicit edits are sent as parsed, typed JSON.
    const metadata = editing && !metadataDirty ? undefined : (parseMetadataObject(metadataText) ?? {})
    onSave({
      providerCode: providerCode.trim(),
      displayName: displayName.trim(),
      externalAccountRef: externalAccountRef.trim(),
      status,
      metadata,
    })
  }

  return (
    <Modal
      open
      title={title}
      okText={submitting ? '正在保存…' : editing ? '保存' : '创建'}
      okButtonProps={{ disabled: !providerCode.trim() || !displayName.trim() || submitting || metadataProblem !== null }}
      onOk={submit}
      onCancel={onCancel}
    >
      <div style={{ display: 'grid', gap: 12 }}>
        {error && <Alert type="error" role="alert" message={error.detail || error.title} showIcon />}
        <label>
          服务商编码
          <Input aria-label="服务商编码" value={providerCode} disabled={editing} onChange={(event) => setProviderCode(event.target.value)} placeholder="AWS、GCP、AZURE 等" />
        </label>
        <label>
          显示名称
          <Input aria-label="显示名称" value={displayName} onChange={(event) => setDisplayName(event.target.value)} placeholder="生产 AWS" />
        </label>
        <label>
          外部账号引用
          <Input aria-label="外部账号引用" value={externalAccountRef} onChange={(event) => setExternalAccountRef(event.target.value)} placeholder="可选的云服务商账号引用" />
        </label>
        {editing && (
          <label>
            状态
            <Select aria-label="状态" value={status} options={[...MASTER_DATA_STATUS_OPTIONS]} onChange={setStatus} />
          </label>
        )}

        <div>
          <Typography.Text strong>元数据</Typography.Text>
          <Input.TextArea
            aria-label="元数据 JSON"
            rows={6}
            value={metadataText}
            onChange={(event) => changeMetadata(event.target.value)}
            style={{ marginTop: 8 }}
          />
          {metadataProblem && (
            <Alert
              type="warning"
              role="alert"
              style={{ marginTop: 8 }}
              message={metadataProblem}
              showIcon
            />
          )}
        </div>
      </div>
    </Modal>
  )
}
