import { useCallback, useState } from 'react'
import { Button, Upload, Typography } from 'antd'
import { UploadOutlined, DownloadOutlined } from '@ant-design/icons'
import { expenseApi } from '../api/expenseApi'
import { toProblemDetail } from '../../../api/problem'

interface ExpenseEvidenceUploadProps {
  expenseId: string
  evidenceId: string | null
  expectedVersion: number
  onChanged: () => void
}

export function ExpenseEvidenceUpload({ expenseId, evidenceId, expectedVersion, onChanged }: ExpenseEvidenceUploadProps) {
  const [uploading, setUploading] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)

  const handleUpload = useCallback(async (file: File) => {
    setUploading(true)
    setProblem(null)
    try {
      await expenseApi.uploadEvidence(expenseId, file, expectedVersion)
      onChanged()
    } catch (error) {
      const detail = toProblemDetail(error)
      setProblem(`${detail.title}：${detail.detail ?? detail.code}`)
    } finally {
      setUploading(false)
    }
    return false // prevent antd default upload
  }, [expenseId, expectedVersion, onChanged])

  return (
    <div>
      <Typography.Text strong>主凭证</Typography.Text>
      <div style={{ marginTop: 8 }}>
        {evidenceId && (
          <Button
            icon={<DownloadOutlined />}
            href={expenseApi.evidenceDownloadUrl(expenseId)}
            target="_blank"
            rel="noopener noreferrer"
            style={{ marginRight: 8 }}
          >
            下载凭证
          </Button>
        )}
        <Upload
          beforeUpload={handleUpload}
          showUploadList={false}
          accept="application/pdf,image/*"
        >
          <Button icon={<UploadOutlined />} loading={uploading}>
            {evidenceId ? '替换凭证' : '上传凭证'}
          </Button>
        </Upload>
        {evidenceId && <Typography.Text type="secondary" style={{ marginLeft: 8 }}>已上传</Typography.Text>}
      </div>
      {problem && <Typography.Text type="danger" style={{ marginTop: 8, display: 'block' }}>{problem}</Typography.Text>}
    </div>
  )
}
