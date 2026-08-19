import { useMutation } from '@tanstack/react-query'
import { Alert, Button, Space } from 'antd'
import { problemDetail as presentProblemDetail, problemSummary, toProblemDetail } from '../../api/problem'

export interface PostingActionProps {
  label?: string
  disabled?: boolean
  disabledReason?: string
  onPost: () => Promise<unknown>
  onCompleted: () => void
}

/** Explicit financial mutation: React Query never retries a posting command. */
export function PostingAction({ label = '记账', disabled = false, disabledReason, onPost, onCompleted }: PostingActionProps) {
  const mutation = useMutation({
    mutationFn: onPost,
    retry: false,
    onSuccess: onCompleted,
  })
  const problem = mutation.error ? toProblemDetail(mutation.error) : null
  return (
    <Space orientation="vertical" size="small" style={{ alignItems: 'flex-start' }}>
      <Button
        type="primary"
        loading={mutation.isPending}
        disabled={disabled || mutation.isPending}
        onClick={() => mutation.mutate()}
      >
        {label}
      </Button>
      {disabledReason && <span className="ledger-action-hint">{disabledReason}</span>}
      {problem && <Alert type="error" showIcon title={problemSummary(problem)} description={presentProblemDetail(problem) ?? undefined} />}
    </Space>
  )
}
