import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Checkbox, Input, Select } from 'antd'
import { useState } from 'react'
import { toProblemDetail } from '../../api/problem'
import { useAuth } from '../auth/AuthSessionProvider'
import { hasPermission } from '../auth/permissions'
import { coverageApi } from './api/coverageApi'
import type { CoverageScope, CoverageStatus } from './api/coverageTypes'
import { formatMoney } from './format'
import './history-coverage.css'

const SCOPE_LABEL: Record<CoverageScope, string> = { ORGANIZATION: '组织', PROJECT: '项目', TEAM: '团队', COST_CENTER: '成本中心' }
const OBSERVATION_LABEL = { OBSERVED: '实际记账', REVIEWED_ZERO: '复核确认的零金额', UNKNOWN: '未知' }
function shiftDay(day: string, offset: number): string {
  const date = new Date(`${day}T00:00:00Z`)
  date.setUTCDate(date.getUTCDate() + offset)
  return date.toISOString().slice(0, 10)
}
function errorDetail(error: unknown): string {
  const problem = toProblemDetail(error)
  return problem.code === 'STATE_CONFLICT' ? '覆盖已变更，请核对最新状态后重新确认。'
    : problem.detail ?? '请求未完成，请稍后重试。'
}

/** Explicit financial review, with a separate action to regenerate current derived evidence. */
export function HistoryCoverageSection({ currency }: { currency: string }) {
  const { user } = useAuth()
  const client = useQueryClient()
  const canRead = hasPermission(user?.permissions, 'COST_READ')
  const canManage = hasPermission(user?.permissions, 'COST_COVERAGE_MANAGE')
  const org = user?.organizationId
  const today = new Date().toISOString().slice(0, 10)
  const [open, setOpen] = useState(false)
  const [lastDay, setLastDay] = useState(() => shiftDay(today, -2))
  const [selection, setSelection] = useState<string | null>(null)
  const [search, setSearch] = useState('')
  const [editing, setEditing] = useState<{ day: string; status: CoverageStatus } | null>(null)
  const [note, setNote] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const [notice, setNotice] = useState('')
  const [noticeError, setNoticeError] = useState(false)
  const value = selection ?? `ORGANIZATION:${org ?? ''}`
  const [scope, scopeId] = value.split(':') as [CoverageScope, string]
  const start = shiftDay(lastDay, -28)
  const end = shiftDay(lastDay, 1)
  const scopes = useQuery({ queryKey: ['history-coverage-scopes', org, search], queryFn: () => coverageApi.scopes(search), enabled: open && canRead && Boolean(org) })
  const report = useQuery({
    queryKey: ['history-coverage', org, currency, scope, scopeId, start, end],
    queryFn: () => coverageApi.coverage(currency, scope, scopeId, start, end),
    enabled: open && canRead && Boolean(org), retry: false,
  })
  const choices = [{ value: `ORGANIZATION:${org}`, label: '本组织' }, ...(scopes.data ?? [])
    .filter(option => option.scope !== 'ORGANIZATION')
    .map(option => ({ value: `${option.scope}:${option.scopeId}`, label: `${SCOPE_LABEL[option.scope]} · ${option.name}` }))]
  const invalidate = async () => {
    await Promise.all([
      client.invalidateQueries({ queryKey: ['history-coverage'] }),
      client.invalidateQueries({ queryKey: ['intelligence'] }),
    ])
  }
  const save = useMutation({
    mutationFn: async () => {
      if (!editing || !report.data) throw new Error('覆盖状态尚未加载。')
      return coverageApi.review({ currency, scope, scopeId, postingDay: editing.day, status: editing.status,
        reviewNote: note.trim(), expectedRevision: report.data.revision })
    },
    onSuccess: async () => {
      setEditing(null); setNote(''); setConfirmed(false)
      setNotice('复核已保存。点击“重新分析”更新当前预测。'); setNoticeError(false)
      await invalidate()
    },
    onError: async error => {
      setNotice(errorDetail(error)); setNoticeError(true); setConfirmed(false)
      await client.invalidateQueries({ queryKey: ['history-coverage'] })
    },
  })
  const analyze = useMutation({
    mutationFn: () => coverageApi.analyze(currency),
    onSuccess: async result => {
      setNotice(result.status === 'COMPLETED' ? '分析已完成。有效历史不足时，预测列表仍为空。' : `分析未完成，状态：${result.status}。`)
      setNoticeError(result.status !== 'COMPLETED')
      await invalidate()
    },
    onError: error => { setNotice(errorDetail(error)); setNoticeError(true) },
  })
  const pending = save.isPending || analyze.isPending
  const canSave = !pending && Boolean(note.trim()) && Boolean(report.data) && !report.isFetching
    && !report.error && (editing?.status === 'REVOKED' || confirmed)
  if (!canRead || !org) return null

  return (
    <section className="v3-section history-coverage" aria-labelledby="history-coverage-title">
      <div className="history-coverage-heading">
        <div><h2 id="history-coverage-title">历史覆盖复核</h2><p>缺少记账记录的日期默认为未知。财务复核可明确记录当天的零金额覆盖。</p></div>
        <Button aria-expanded={open} aria-controls="history-coverage-content" onClick={() => setOpen(!open)} disabled={pending}>{open ? '收起复核' : '展开复核'}</Button>
      </div>
      {open && <div id="history-coverage-content">
        <p className="v3-note">复核是一项人工财务声明，表示已核对所选范围当天的全部预期成本记账来源。导入成功不等于完整采集；声明只作用于所选范围及币种。后续成本入账会使旧声明失效。</p>
        <p className="v3-footnote">本页采用网关、对账调整与直接账单的有效成本口径。报销及 Advisor 自身费用仍按原有规则计入财务账本和预算实际支出。</p>
        <div className="history-coverage-controls">
          <label><span>复核范围</span><Select showSearch filterOption={false} value={value} options={choices} onSearch={setSearch}
            aria-label="复核范围" loading={scopes.isLoading} disabled={pending}
            onChange={next => { setSelection(next); setEditing(null); setNote(''); setConfirmed(false); setNotice('') }} /></label>
          <label><span>最后记账日（UTC）</span><input type="date" value={lastDay} max={shiftDay(today, -1)} disabled={pending}
            onChange={event => { if (event.target.value) { setLastDay(event.target.value); setEditing(null); setNotice('') } }} /></label>
          {canManage && <Button onClick={() => analyze.mutate()} loading={analyze.isPending} disabled={pending || Boolean(editing)}>重新分析</Button>}
        </div>
        <p className="v3-footnote">查看 {start} 至 {lastDay} 的 29 个 UTC 记账日，币种 {currency}。重新分析使用当前分析窗口；查看其他日期不会改变分析范围。</p>
        {!canManage && <p className="v3-note">你可以查看覆盖状态。确认及撤销由具有覆盖复核权限的财务人员操作。</p>}
        {scopes.error && <Alert type="error" showIcon message="范围列表加载失败" description={errorDetail(scopes.error)} />}
        {report.error && <Alert type="error" showIcon message="覆盖状态加载失败" description={errorDetail(report.error)} />}
        {notice && <Alert role="status" type={noticeError ? 'error' : 'success'} showIcon message={notice} />}
        {report.isLoading && <div className="v3-figure" role="status" aria-label="正在加载覆盖状态" />}
        {report.data && <div className="history-coverage-table" tabIndex={0} aria-label="覆盖状态表，可滚动查看">
          <table><caption>日期覆盖状态 · {currency}</caption><thead><tr><th scope="col">UTC 记账日</th><th scope="col">观测依据</th><th scope="col">金额</th><th scope="col">复核状态</th>{canManage && <th scope="col">操作</th>}</tr></thead>
            <tbody>{[...report.data.days].reverse().map(day => <tr key={day.postingDay}>
              <th scope="row">{day.postingDay}</th><td>{OBSERVATION_LABEL[day.observation]}</td>
              <td>{day.amount === null ? '未知' : formatMoney(day.amount, currency)}</td>
              <td>{day.reviewValid ? '声明有效' : day.review?.status === 'REVOKED' ? '已撤销' : day.review ? '声明已失效' : '尚未复核'}</td>
              {canManage && <td className="history-coverage-actions"><Button size="small" aria-label={`复核 ${day.postingDay}`} disabled={pending} onClick={() => {
                setEditing({ day: day.postingDay, status: 'REVIEWED_COMPLETE' }); setNote(''); setConfirmed(false); setNotice('')
              }}>复核</Button>{day.review?.status === 'REVIEWED_COMPLETE' && <Button size="small" danger aria-label={`撤销 ${day.postingDay}`} disabled={pending} onClick={() => {
                setEditing({ day: day.postingDay, status: 'REVOKED' }); setNote(''); setConfirmed(false); setNotice('')
              }}>撤销</Button>}</td>}
            </tr>)}</tbody></table>
        </div>}
        {editing && <form className="history-coverage-review" aria-label={`${editing.day} 覆盖复核`} onSubmit={event => { event.preventDefault(); if (canSave) save.mutate() }}>
          <h3>{editing.status === 'REVOKED' ? '撤销覆盖声明' : '确认完整覆盖'} · {editing.day} · {currency}</h3>
          <label><span>{editing.status === 'REVOKED' ? '撤销原因' : '复核依据'}</span><Input.TextArea value={note} maxLength={500} showCount required rows={3}
            aria-label={editing.status === 'REVOKED' ? '撤销原因' : '复核依据'} autoFocus disabled={pending} onChange={event => setNote(event.target.value)} /></label>
          {editing.status === 'REVIEWED_COMPLETE' && <Checkbox checked={confirmed} disabled={pending} onChange={event => setConfirmed(event.target.checked)}>我已核对所选范围当日的全部预期成本记账来源，可以确认完整覆盖。</Checkbox>}
          <div className="history-coverage-actions"><Button type="primary" danger={editing.status === 'REVOKED'} htmlType="submit" loading={save.isPending}
            disabled={!canSave}>{editing.status === 'REVOKED' ? '保存撤销' : '保存复核'}</Button>
            <Button disabled={pending} onClick={() => setEditing(null)}>取消</Button></div>
        </form>}
      </div>}
    </section>
  )
}
