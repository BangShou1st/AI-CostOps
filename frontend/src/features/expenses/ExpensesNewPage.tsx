import { useCallback, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Alert, Card } from 'antd'
import { expenseApi } from './api/expenseApi'
import { ExpenseForm } from './components/ExpenseForm'
import { problemDetail as presentProblemDetail, toProblemDetail } from '../../api/problem'
import type { CreateExpenseBody } from './api/expenseApi'

export function ExpensesNewPage() {
  const navigate = useNavigate()
  const [loading, setLoading] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)

  const handleSubmit = useCallback(async (body: CreateExpenseBody) => {
    setLoading(true); setProblem(null)
    try {
      const result = await expenseApi.create(body, crypto.randomUUID())
      navigate(`/expenses/${result.id}`)
    } catch (e) {
      setProblem(presentProblemDetail(toProblemDetail(e)) ?? '创建失败')
    } finally {
      setLoading(false)
    }
  }, [navigate])

  return (
    <Card title="新建报销">
      {problem && <Alert type="error" showIcon title={problem} style={{ marginBottom: 16 }} />}
      <ExpenseForm editable={true} onSubmit={handleSubmit} loading={loading} submitLabel="创建" />
    </Card>
  )
}
