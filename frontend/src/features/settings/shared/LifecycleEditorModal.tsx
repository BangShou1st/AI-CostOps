import { Alert, Input, Modal, Select } from 'antd'
import { useState } from 'react'
import type { ProblemDetail } from '../../../api/problem'
import type { MasterDataStatus } from '../api/settingsTypes'

export interface LifecycleFormValues {
  code: string
  name: string
  status: MasterDataStatus
}

export const MASTER_DATA_STATUS_OPTIONS: readonly { value: MasterDataStatus; label: string }[] = [
  { value: 'ACTIVE', label: 'ACTIVE' },
  { value: 'DISABLED', label: 'DISABLED' },
  { value: 'ARCHIVED', label: 'ARCHIVED' },
]

export function LifecycleEditorModal({ title, submitting, error, initial, onCancel, onSave }: {
  title: string
  submitting: boolean
  error: ProblemDetail | null
  initial?: LifecycleFormValues
  onCancel: () => void
  onSave: (values: LifecycleFormValues) => void
}) {
  const editing = initial !== undefined
  const [code, setCode] = useState(initial?.code ?? '')
  const [name, setName] = useState(initial?.name ?? '')
  const [status, setStatus] = useState<MasterDataStatus>(initial?.status ?? 'ACTIVE')

  return (
    <Modal
      open
      title={title}
      okText={submitting ? 'Saving…' : editing ? 'Save' : 'Create'}
      okButtonProps={{ disabled: !code.trim() || !name.trim() || submitting }}
      onOk={() => onSave({ code: code.trim(), name: name.trim(), status })}
      onCancel={onCancel}
    >
      <div style={{ display: 'grid', gap: 12 }}>
        {error && <Alert type="error" role="alert" message={error.detail || error.title} showIcon />}
        <label>
          Code
          <Input aria-label="Code" value={code} disabled={editing} onChange={(event) => setCode(event.target.value)} placeholder="Unique code within the organization" />
        </label>
        <label>
          Name
          <Input aria-label="Name" value={name} onChange={(event) => setName(event.target.value)} placeholder="Display name" />
        </label>
        {editing && (
          <label>
            Status
            <Select aria-label="Status" value={status} options={[...MASTER_DATA_STATUS_OPTIONS]} onChange={setStatus} />
          </label>
        )}
      </div>
    </Modal>
  )
}
