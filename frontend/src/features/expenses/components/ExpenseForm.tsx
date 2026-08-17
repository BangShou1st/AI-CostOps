import { Button, DatePicker, Form, InputNumber } from 'antd'
import dayjs from 'dayjs'
import type { CreateExpenseBody, EditExpenseBody } from '../api/expenseApi'

interface ExpenseFormProps {
  expenseDate?: string
  amount?: string
  currency?: string
  editable: boolean
  onSubmit: (body: CreateExpenseBody | EditExpenseBody) => void
  loading?: boolean
  submitLabel?: string
}

/** Expense body editor for DRAFT / NEEDS_INFO statuses. Money is entered as a
 * normal decimal Antd <InputNumber> and the component converts to an 8-decimal
 * string on submit; the backend rejects any value that does not fit DECIMAL(20,8)
 * at the API boundary. */
export function ExpenseForm({
  expenseDate,
  amount,
  currency = 'CNY',
  editable,
  onSubmit,
  loading = false,
  submitLabel = '保存',
}: ExpenseFormProps) {
  const initialValues = {
    expenseDate: expenseDate ? dayjs(expenseDate) : dayjs(),
    amount: amount ? parseFloat(amount) : undefined,
    currency,
  }

  const handleFinish = (values: { expenseDate: dayjs.Dayjs; amount: number; currency: string }) => {
    onSubmit({
      expenseDate: values.expenseDate.format('YYYY-MM-DD'),
      amount: values.amount.toFixed(8),
      currency: values.currency.toUpperCase(),
    })
  }

  return (
    <Form layout="inline" initialValues={initialValues} onFinish={handleFinish} disabled={!editable}>
      <Form.Item name="expenseDate" label="日期" rules={[{ required: true, message: '请选择日期' }]}>
        <DatePicker />
      </Form.Item>
      <Form.Item name="amount" label="金额" rules={[{ required: true, message: '请输入金额' }]}>
        <InputNumber min={0} step={0.01} precision={8} style={{ width: 160 }} disabled={!editable} />
      </Form.Item>
      <Form.Item name="currency" label="币种" rules={[{ required: true, message: '请输入币种' }]}>
        <input style={{ width: 60 }} maxLength={3} disabled={!editable} />
      </Form.Item>
      {editable && (
        <Form.Item>
          <Button type="primary" htmlType="submit" loading={loading}>
            {submitLabel}
          </Button>
        </Form.Item>
      )}
    </Form>
  )
}
