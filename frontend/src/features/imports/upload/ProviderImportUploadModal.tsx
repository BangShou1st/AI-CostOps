import { Alert, Form, Modal, Select, Upload, type UploadFile } from 'antd'
import { useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { problemDetail as presentProblemDetail, problemTitle, toProblemDetail } from '../../../api/problem'
import { settingsApi } from '../../settings/api/settingsApi'
import { settingsKeys } from '../../settings/api/settingsKeys'
import type { ProviderAccount } from '../../settings/api/settingsTypes'
import { importsApi } from '../api/importsApi'
import type { ImportSourceType, ProviderImportResult } from '../api/importTypes'
import { providerSourceTypes } from './providerSourceTypes'
import { READABLE_SELECT_PROPS, readableOption } from '../../../lib/selectPresentation'

export interface ProviderImportUploadResult extends ProviderImportResult {
  duplicateEvidence: boolean
  duplicateBatch: boolean
}

interface ProviderImportUploadModalProps {
  open: boolean
  onClose: () => void
  onUploaded: (result: ProviderImportUploadResult) => void
}

const SOURCE_TYPE_LABELS: Record<ImportSourceType, string> = {
  FILE_EXPORT: '文件导出（FILE_EXPORT）',
  USAGE_API_JSON: '用量 API JSON（USAGE_API_JSON）',
  COSTS_API_JSON: '成本 API JSON（COSTS_API_JSON）',
}

/**
 * Reusable Provider Evidence upload modal. The provider-account directory is
 * only fetched here; callers must gate mounting on BOTH EVIDENCE_UPLOAD_PROVIDER
 * and PROVIDER_ACCOUNT_READ (backend authorization stays authoritative).
 */
export function ProviderImportUploadModal({ open, onClose, onUploaded }: ProviderImportUploadModalProps) {
  const [form] = Form.useForm<{ providerAccountId: string; sourceType?: ImportSourceType; file?: UploadFile }>()
  const [selectedProviderCode, setSelectedProviderCode] = useState<string | undefined>()
  const [file, setFile] = useState<File | undefined>()

  const accountsQuery = useQuery({
    queryKey: settingsKeys.providerAccountsAll(),
    queryFn: async () => {
      const page = await settingsApi.listProviderAccounts(0, 200)
      return page.items.filter((account) => account.status === 'ACTIVE')
    },
    enabled: open,
  })

  const upload = useMutation({
    mutationFn: async (input: { providerAccountId: string; sourceType: ImportSourceType; file: File }) =>
      importsApi.uploadProviderImport(input),
    retry: false,
    onSuccess: (result) => {
      onUploaded(result)
      form.resetFields()
      setSelectedProviderCode(undefined)
      setFile(undefined)
    },
  })

  const handleAccountChange = (accountId: string) => {
    const account = accountsQuery.data?.find((candidate) => candidate.id === accountId)
    setSelectedProviderCode(account?.providerCode)
    form.setFieldValue('sourceType', undefined)
  }

  const availableTypes = selectedProviderCode
    ? providerSourceTypes(selectedProviderCode)
    : undefined
  const unsupported = selectedProviderCode !== undefined && availableTypes === undefined

  const submit = async () => {
    if (!file) return
    try {
      const { providerAccountId, sourceType } = await form.validateFields()
      if (!providerAccountId || !sourceType) return
      upload.mutate({ providerAccountId, sourceType, file })
    } catch {
      // Field-level validation errors are rendered inline by the Form items.
    }
  }

  return (
    <Modal
      open={open}
      title="上传 Provider 账单"
      onCancel={onClose}
      onOk={() => void submit()}
      okText="上传"
      cancelText="取消"
      confirmLoading={upload.isPending}
      okButtonProps={{ disabled: !file || unsupported }}
    >
      <Form form={form} layout="vertical" disabled={upload.isPending}>
        <Form.Item
          name="providerAccountId"
          label="Provider 账号"
          rules={[{ required: true, message: '请选择 Provider 账号' }]}
        >
          <Select
            {...READABLE_SELECT_PROPS}
            style={{ width: '100%' }}
            placeholder="选择 Provider 账号"
            options={(accountsQuery.data ?? []).map((account: ProviderAccount) => ({
              ...readableOption(account.id, account.displayName),
            }))}
            onChange={handleAccountChange}
            loading={accountsQuery.isLoading}
          />
        </Form.Item>
        <Form.Item name="sourceType" label="来源类型" rules={[{ required: true, message: '请选择来源类型' }]}>
          <Select
            {...READABLE_SELECT_PROPS}
            style={{ width: '100%' }}
            placeholder="选择来源类型"
            disabled={unsupported || !selectedProviderCode}
            options={(availableTypes ?? []).map((type) => readableOption(type, SOURCE_TYPE_LABELS[type]))}
          />
        </Form.Item>
        <Form.Item label="文件" required>
          <Upload
            beforeUpload={(uploaded) => {
              setFile(uploaded)
              return false
            }}
            onRemove={() => {
              setFile(undefined)
              form.setFieldValue('file', undefined)
            }}
            maxCount={1}
            fileList={file ? [{ uid: 'file', name: file.name }] : []}
          >
            <button type="button">选择文件</button>
          </Upload>
        </Form.Item>
        {unsupported && (
          <Alert
            type="warning"
            showIcon
            title="该 Provider 暂不支持上传"
            description="当前 Provider 没有已注册的 M2 上传来源类型。"
          />
        )}
        {upload.isError && (() => {
          const problem = toProblemDetail(upload.error)
          return (
            <Alert
              type="error"
              showIcon
              title={problemTitle(problem)}
              description={presentProblemDetail(problem) ?? '请稍后重试。'}
            />
          )
        })()}
      </Form>
    </Modal>
  )
}
