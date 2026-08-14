import { Alert, Input, Modal, Select } from 'antd'
import { useState } from 'react'
import type { ProblemDetail } from '../../../api/problem'
import type { MasterDataStatus } from '../api/settingsTypes'
import { statusLabel } from '../presentation'

export interface LifecycleFormValues {
  code: string
  name: string
  status: MasterDataStatus
}

export const MASTER_DATA_STATUS_OPTIONS: readonly { value: MasterDataStatus; label: string }[] = [
  { value: 'ACTIVE', label: statusLabel('ACTIVE') },
  { value: 'DISABLED', label: statusLabel('DISABLED') },
  { value: 'ARCHIVED', label: statusLabel('ARCHIVED') },
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
      okText={submitting ? '正在保存…' : editing ? '保存' : '创建'}
      okButtonProps={{ disabled: !code.trim() || !name.trim() || submitting }}
      onOk={() => onSave({ code: code.trim(), name: name.trim(), status })}
      onCancel={onCancel}
    >
      <div style={{ display: 'grid', gap: 12 }}>
        {error && <Alert type="error" role="alert" message={error.detail || error.title} showIcon />}
        <label>
          编码
          <Input aria-label="编码" value={code} disabled={editing} onChange={(event) => setCode(event.target.value)} placeholder="组织内唯一编码" />
        </label>
        <label>
          名称
          <Input aria-label="名称" value={name} onChange={(event) => setName(event.target.value)} placeholder="显示名称" />
        </label>
        {editing && (
          <label>
            状态
            <Select aria-label="状态" value={status} options={[...MASTER_DATA_STATUS_OPTIONS]} onChange={setStatus} />
          </label>
        )}
      </div>
    </Modal>
  )
}
