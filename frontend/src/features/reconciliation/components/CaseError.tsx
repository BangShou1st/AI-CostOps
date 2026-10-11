import { Alert } from 'antd'
import { problemDetail, problemTitle, toProblemDetail } from '../../../api/problem'

export function CaseError({ error }: { error: unknown }) {
  if (!error) return null
  const problem = toProblemDetail(error)
  return <Alert role="alert" type="error" showIcon title="案例操作未完成" description={problemDetail(problem) ?? problemTitle(problem)} />
}
