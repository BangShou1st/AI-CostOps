import { Button, DatePicker, Form, InputNumber } from 'antd'
import dayjs from 'dayjs'
import { formatDecimal8, parseUserDecimal8 } from '../../../lib/money'
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

/** Keep money as a decimal string throughout; never round through a JS number. */
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
    amount,
    currency,
  }

  const handleFinish = (values: { expenseDate: dayjs.Dayjs; amount: string; currency: string }) => {
    onSubmit({
      expenseDate: values.expenseDate.format('YYYY-MM-DD'),
      amount: formatDecimal8(parseUserDecimal8(values.amount)),
      currency: values.currency.toUpperCase(),
    })
  }

  return (
    <Form layout="inline" initialValues={initialValues} onFinish={handleFinish} disabled={!editable || loading}>
      <Form.Item name="expenseDate" label="日期" rules={[{ required: true, message: '请选择日期' }]}>
        <DatePicker />
      </Form.Item>
      <Form.Item name="amount" label="金额" rules={[
        { required: true, message: '请输入金额' },
        { validator: async (_, value: string | null) => {
          if (!value) return
          try {
            const units = parseUserDecimal8(value)
            if (units <= -100000000000000000000n || units >= 100000000000000000000n) throw new Error()
          } catch { throw new Error('金额最多12位整数、8位小数，请勿使用科学计数法') }
        } },
      ]}>
        <InputNumber<string> stringMode step="0.01" style={{ width: 200 }} disabled={!editable || loading} />
      </Form.Item>
      <Form.Item name="currency" label="币种" rules={[{ required: true, message: '请输入币种' }, { pattern: /^[A-Za-z]{3}$/, message: '请输入3位字母币种代码' }]}>
        <input style={{ width: 60 }} maxLength={3} disabled={!editable || loading} />
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
