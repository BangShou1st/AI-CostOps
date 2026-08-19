import { useCallback, useState } from 'react'
import { Button, Upload, Typography } from 'antd'
import { UploadOutlined, DownloadOutlined } from '@ant-design/icons'
import { expenseApi } from '../api/expenseApi'
import { problemDetail as presentProblemDetail, problemTitle, toProblemDetail } from '../../../api/problem'

interface ExpenseEvidenceSectionProps {
  /** Employee pages show upload/replace; finance review pages never do. */
  mode: 'employee' | 'finance'
  /** Caller decides when upload/replace is allowed (DRAFT / NEEDS_INFO only). */
  canUpload: boolean
  expenseId: string
  evidenceId: string | null
  expectedVersion: number
  onChanged: () => void
}

/**
 * Primary-evidence presenter for both the employee detail and the finance
 * review detail. Download is always authenticated through the apiClient (the
 * Bearer token is attached by the axios interceptor), never a naked URL, and
 * is shown whenever the claim already carries an evidence id — regardless of
 * claim status. Upload/replace is employee-only and gated by the caller.
 */
export function ExpenseEvidenceSection({
  mode,
  canUpload,
  expenseId,
  evidenceId,
  expectedVersion,
  onChanged,
}: ExpenseEvidenceSectionProps) {
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
      setProblem(`${problemTitle(detail)}：${presentProblemDetail(detail) ?? detail.code}`)
    } finally {
      setUploading(false)
    }
    return false // prevent antd default upload
  }, [expenseId, expectedVersion, onChanged])

  const handleDownload = useCallback(async () => {
    setProblem(null)
    try {
      const blob = await expenseApi.downloadEvidence(expenseId)
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = `evidence-${expenseId}`
      document.body.appendChild(anchor)
      anchor.click()
      document.body.removeChild(anchor)
      URL.revokeObjectURL(url)
    } catch (error) {
      const detail = toProblemDetail(error)
      setProblem(`${problemTitle(detail)}：${presentProblemDetail(detail) ?? detail.code}`)
    }
  }, [expenseId])

  const showUpload = mode === 'employee' && canUpload
  const showDownload = evidenceId != null

  return (
    <div>
      <Typography.Text strong>主凭证</Typography.Text>
      <div style={{ marginTop: 8 }}>
        {showDownload && (
          <Button icon={<DownloadOutlined />} onClick={handleDownload} style={{ marginRight: 8 }}>
            下载凭证
          </Button>
        )}
        {showUpload && (
          <Upload
            beforeUpload={handleUpload}
            showUploadList={false}
            accept="application/pdf,image/*"
          >
            <Button icon={<UploadOutlined />} loading={uploading}>
              {evidenceId ? '替换凭证' : '上传凭证'}
            </Button>
          </Upload>
        )}
        {evidenceId && <Typography.Text type="secondary" style={{ marginLeft: 8 }}>已上传</Typography.Text>}
      </div>
      {problem && <Typography.Text type="danger" style={{ marginTop: 8, display: 'block' }}>{problem}</Typography.Text>}
    </div>
  )
}
