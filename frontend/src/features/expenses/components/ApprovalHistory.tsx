import { Timeline, Typography } from 'antd'
import type { ApprovalActionResponse } from '../api/expenseApi'
import { formatEventDateTime } from '../../../lib/dateTime'

const ACTION_LABELS: Record<string, string> = {
  SUBMIT: '提交',
  REQUEST_INFO: '要求补充信息',
  RESUBMIT: '重新提交',
  APPROVE: '批准',
  REJECT: '拒绝',
  CANCEL: '取消',
}

interface ApprovalHistoryProps {
  history: ApprovalActionResponse[]
}

export function ApprovalHistory({ history }: ApprovalHistoryProps) {
  if (history.length === 0) return <Typography.Text type="secondary">暂无审批记录</Typography.Text>

  return (
    <Timeline
      items={history.map((action) => ({
        content: (
          <>
            <Typography.Text strong>{ACTION_LABELS[action.actionType] ?? action.actionType}</Typography.Text>
            {action.comment && (
              <Typography.Paragraph style={{ margin: '4px 0 0', fontStyle: 'italic' }}>
                {action.comment}
              </Typography.Paragraph>
            )}
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {formatEventDateTime(action.createdAt)}
            </Typography.Text>
          </>
        ),
      }))}
    />
  )
}
