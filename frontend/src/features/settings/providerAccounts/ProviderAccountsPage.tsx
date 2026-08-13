import { useQueryClient } from '@tanstack/react-query'
import { useQuery } from '@tanstack/react-query'
import { Alert, Button, Input, Modal, Select, Table, Typography } from 'antd'
import type { TableProps } from 'antd'
import { useState } from 'react'
import { toProblemDetail, type ProblemDetail } from '../../../api/problem'
import { useAuth } from '../../auth/AuthSessionProvider'
import { settingsApi } from '../api/settingsApi'
import { settingsKeys } from '../api/settingsKeys'
import type { MasterDataStatus, ProviderAccount } from '../api/settingsTypes'
import { hasPermission } from '../permissions'
import { useAuthorizationMutation } from '../useAuthorizationMutation'
import { MASTER_DATA_STATUS_OPTIONS } from '../shared/LifecycleEditorModal'

const SECRET_KEY_PATTERN = /password|token|secret|apikey/i

export function isSecretMetadataKey(key: string): boolean {
  return SECRET_KEY_PATTERN.test(key)
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
      metadata: Record<string, unknown>
    }) => {
      if (editor?.mode === 'edit') {
        return settingsApi.updateProviderAccount(editor.record.id, {
          displayName: input.displayName,
          externalAccountRef: input.externalAccountRef || undefined,
          status: input.status,
          metadata: input.metadata,
        })
      }
      return settingsApi.createProviderAccount({
        providerCode: input.providerCode,
        displayName: input.displayName,
        externalAccountRef: input.externalAccountRef || undefined,
        metadata: input.metadata,
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
    { title: 'Provider', dataIndex: 'providerCode', key: 'providerCode' },
    { title: 'Display name', dataIndex: 'displayName', key: 'displayName' },
    { title: 'External account ref', dataIndex: 'externalAccountRef', key: 'externalAccountRef', render: (value: string | null) => value ?? '—' },
    { title: 'Status', dataIndex: 'status', key: 'status', render: (status: string) => status.toLowerCase() },
    {
      title: 'Actions', key: 'actions',
      render: (_, record) => canManage ? (
        <Button size="small" onClick={() => { setProblem(null); setEditor({ mode: 'edit', record }) }}>Edit</Button>
      ) : null,
    },
  ]

  return (
    <main className="settings-page">
      <div className="settings-toolbar">
        <div>
          <h1>Provider accounts</h1>
          <Typography.Text type="secondary">Manage cloud provider accounts for the organization.</Typography.Text>
        </div>
        {canManage && <Button type="primary" onClick={() => { setProblem(null); setEditor({ mode: 'create' }) }}>Create provider account</Button>}
      </div>
      {listQuery.isLoading && <div role="status">Loading provider accounts…</div>}
      {listQuery.isError && (
        <Alert type="error" role="alert" message={toProblemDetail(listQuery.error).detail || toProblemDetail(listQuery.error).title} showIcon />
      )}
      {listQuery.data && listQuery.data.items.length === 0 && <div className="settings-empty">No provider accounts in this organization.</div>}
      {listQuery.data && listQuery.data.items.length > 0 && (
        <Table<ProviderAccount>
          rowKey="id"
          columns={columns}
          dataSource={listQuery.data.items}
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
          title={editor.mode === 'edit' ? `Edit provider account ${editor.record.providerCode}` : 'Create provider account'}
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

interface MetadataEntry {
  key: string
  value: string
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
    metadata: Record<string, unknown>
  }) => void
}) {
  const editing = initial !== undefined
  const [providerCode, setProviderCode] = useState(initial?.providerCode ?? '')
  const [displayName, setDisplayName] = useState(initial?.displayName ?? '')
  const [externalAccountRef, setExternalAccountRef] = useState(initial?.externalAccountRef ?? '')
  const [status, setStatus] = useState<MasterDataStatus>(initial?.status ?? 'ACTIVE')
  const [entries, setEntries] = useState<MetadataEntry[]>(
    initial ? Object.entries(initial.metadata).map(([key, value]) => ({ key, value: String(value) })) : [],
  )
  const [newKey, setNewKey] = useState('')
  const [newValue, setNewValue] = useState('')
  const [secretKeyError, setSecretKeyError] = useState(false)

  const metadata = Object.fromEntries(entries.filter((entry) => entry.key.trim()).map((entry) => [entry.key.trim(), entry.value]))

  function addMetadata() {
    const key = newKey.trim()
    if (!key) return
    if (isSecretMetadataKey(key)) {
      setSecretKeyError(true)
      return
    }
    setSecretKeyError(false)
    setEntries([...entries, { key, value: newValue }])
    setNewKey('')
    setNewValue('')
  }

  return (
    <Modal
      open
      title={title}
      okText={submitting ? 'Saving…' : editing ? 'Save' : 'Create'}
      okButtonProps={{ disabled: !providerCode.trim() || !displayName.trim() || submitting || secretKeyError }}
      onOk={() => onSave({ providerCode: providerCode.trim(), displayName: displayName.trim(), externalAccountRef: externalAccountRef.trim(), status, metadata })}
      onCancel={onCancel}
    >
      <div style={{ display: 'grid', gap: 12 }}>
        {error && <Alert type="error" role="alert" message={error.detail || error.title} showIcon />}
        <label>
          Provider code
          <Input aria-label="Provider code" value={providerCode} disabled={editing} onChange={(event) => setProviderCode(event.target.value)} placeholder="AWS, GCP, AZURE, …" />
        </label>
        <label>
          Display name
          <Input aria-label="Display name" value={displayName} onChange={(event) => setDisplayName(event.target.value)} placeholder="Production AWS" />
        </label>
        <label>
          External account ref
          <Input aria-label="External account ref" value={externalAccountRef} onChange={(event) => setExternalAccountRef(event.target.value)} placeholder="Optional provider account reference" />
        </label>
        {editing && (
          <label>
            Status
            <Select aria-label="Status" value={status} options={[...MASTER_DATA_STATUS_OPTIONS]} onChange={setStatus} />
          </label>
        )}

        <div>
          <Typography.Text strong>Metadata</Typography.Text>
          {entries.map((entry, index) => (
            <div key={`${entry.key}-${index}`} style={{ display: 'flex', gap: 8, marginTop: 8 }}>
              <Input aria-label={`Metadata key ${index}`} value={entry.key} readOnly />
              <Input aria-label={`Metadata value ${index}`} value={entry.value} readOnly />
              <Button onClick={() => setEntries(entries.filter((_, i) => i !== index))}>Remove</Button>
            </div>
          ))}
          <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
            <Input aria-label="Metadata key" value={newKey} placeholder="Key" onChange={(event) => setNewKey(event.target.value)} />
            <Input aria-label="Metadata value" value={newValue} placeholder="Value" onChange={(event) => setNewValue(event.target.value)} />
            <Button onClick={addMetadata}>Add metadata</Button>
          </div>
          {secretKeyError && (
            <Alert
              type="warning"
              role="alert"
              style={{ marginTop: 8 }}
              message="Metadata keys may not contain password, token, secret or apikey fragments."
              showIcon
            />
          )}
        </div>
      </div>
    </Modal>
  )
}
