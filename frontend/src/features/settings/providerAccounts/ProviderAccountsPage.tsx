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
      setMetadataProblem('Provider account metadata must be valid JSON.')
      return
    }
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      setMetadataProblem('Provider account metadata must be a JSON object.')
    } else if (containsSecretMetadataKey(parsed)) {
      setMetadataProblem('Metadata keys may not contain password, token, secret or apikey fragments.')
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
      okText={submitting ? 'Saving…' : editing ? 'Save' : 'Create'}
      okButtonProps={{ disabled: !providerCode.trim() || !displayName.trim() || submitting || metadataProblem !== null }}
      onOk={submit}
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
          <Input.TextArea
            aria-label="Metadata JSON"
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
