import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Empty, InputNumber, Select } from 'antd'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { toProblemDetail, type ProblemDetail } from '../../api/problem'
import { useAuth } from '../auth/AuthSessionProvider'
import { hasPermission } from '../settings/permissions'
import { useAuthorizationMutation } from '../settings/useAuthorizationMutation'
import { advisorApi } from './api/advisorApi'
import { advisorKeys } from './api/advisorKeys'
import { ADVISOR_JOB_ORDER, ADVISOR_SUBJECT_TYPES, type AdvisorJob, type AdvisorProfile } from './api/advisorTypes'
import '../../features/intelligence/v3-tokens.css'
import './advisor.css'

export interface AdvisorPreview {
  profile: AdvisorProfile
  job: AdvisorJob
}

const JOB_PILL: Record<string, string> = {
  PENDING: 'v3-pill-neutral',
  CLAIMED: 'v3-pill-info',
  DISPATCHING: 'v3-pill-info',
  RUNNING: 'v3-pill-watch',
  COMPLETED: 'v3-pill-ok',
  FAILED: 'v3-pill-risk',
}

const SUBJECT_EVIDENCE: Record<string, string> = {
  ANOMALY: '/intelligence/anomalies',
  FORECAST: '/intelligence/forecasts',
  BUDGET_RISK: '/intelligence/overview',
  SAVINGS: '/intelligence/savings',
}

const TERMINAL = new Set(['COMPLETED', 'FAILED'])

/** Governed AI Advisor: deterministic evidence plus AI narrative. preview is DEV-only. */
export function AdvisorPage(props: { preview?: AdvisorPreview }) {
  const auth = useAuth()
  const queryClient = useQueryClient()
  const preview = props.preview
  const canManage = hasPermission(auth.user?.permissions, 'AI_ADVISOR_MANAGE')
  const [subjectType, setSubjectType] = useState<string>('ANOMALY')
  const [subjectId, setSubjectId] = useState<number | null>(null)
  const [jobId, setJobId] = useState<number | null>(null)
  const [problem, setProblem] = useState<ProblemDetail | null>(null)
  const [editingProfile, setEditingProfile] = useState(false)

  const profileQuery = useQuery({
    queryKey: advisorKeys.profile(),
    queryFn: () => advisorApi.profile(),
    enabled: !preview,
    staleTime: 30_000,
  })
  const profile = preview?.profile ?? profileQuery.data ?? null

  const jobQuery = useQuery({
    queryKey: advisorKeys.job(jobId),
    queryFn: () => advisorApi.explanation(jobId as number),
    enabled: !preview && jobId !== null,
    refetchInterval: (query) => {
      const status = query.state.data?.status
      return status && !TERMINAL.has(status) ? 5000 : false
    },
  })
  const job = preview?.job ?? jobQuery.data ?? null

  const request = useAuthorizationMutation({
    mutationFn: (input: { subjectType: string; subjectId: number }) => advisorApi.requestExplanation(input.subjectType, input.subjectId),
    onSuccess: (created) => { setProblem(null); setJobId(created.id); void queryClient.invalidateQueries({ queryKey: advisorKeys.all }) },
    onError: (error) => setProblem(toProblemDetail(error)),
  })
  const retry = useAuthorizationMutation({
    mutationFn: (id: number) => advisorApi.retry(id),
    onSuccess: (next) => { setProblem(null); void queryClient.invalidateQueries({ queryKey: advisorKeys.job(next.id) }) },
    onError: (error) => setProblem(toProblemDetail(error)),
  })
  const saveProfile = useAuthorizationMutation({
    mutationFn: advisorApi.updateProfile,
    onSuccess: () => { setProblem(null); setEditingProfile(false); void queryClient.invalidateQueries({ queryKey: advisorKeys.profile() }) },
    onError: (error) => setProblem(toProblemDetail(error)),
  })

  return (
    <main className="v3-page">
      <header>
        <span className="v3-eyebrow">AI Advisor · 受治理的解释</span>
        <h1>AI Advisor</h1>
        <p className="v3-lede">只解释后端已经计算的确定性事实，不计算金额。执行受 Gateway 治理、计量与结算。</p>
      </header>
      {problem && <div role="alert"><Alert type="error" showIcon closable onClose={() => setProblem(null)} message="请求失败" description={problemText(problem)} /></div>}
      <section className="v3-section" aria-labelledby="v3-advisor-profile">
        <div className="v3-section-head">
          <span className="v3-eyebrow">Profile</span>
          <h2 id="v3-advisor-profile">执行配置</h2>
        </div>
        {!preview && profileQuery.isLoading && <div className="v3-note" role="status">正在加载执行配置…</div>}
        {profile && (
          <dl className="v3-evidence">
            <div><dt>版本</dt><dd>v{profile.version} · {profile.status}</dd></div>
            <div><dt>执行模型</dt><dd>provider model #{profile.providerModelId}</dd></div>
            <div><dt>财务范围</dt><dd>{profile.financialScopeType} #{profile.financialScopeId} · 预算模式 {profile.budgetEnforcementMode}</dd></div>
          </dl>
        )}
        {!preview && !profile && !profileQuery.isLoading && <Empty description="暂无执行配置，请联系管理员。" />}
        {!preview && canManage && !editingProfile && (
          <div className="v3-actions"><button type="button" className="v3-link-button" onClick={() => setEditingProfile(true)}>更新执行配置</button></div>
        )}
        {!preview && canManage && editingProfile && profile && (
          <ProfileForm profile={profile} pending={saveProfile.isPending} onSubmit={(input) => saveProfile.mutate(input)} onCancel={() => setEditingProfile(false)} />
        )}
        {!preview && !canManage && <div className="v3-note" role="status">执行配置由 AI_ADVISOR_MANAGE 权限管理。</div>}
      </section>
      <section className="v3-section" aria-labelledby="v3-advisor-request">
        <div className="v3-section-head">
          <span className="v3-eyebrow">Explain</span>
          <h2 id="v3-advisor-request">请求解释</h2>
          <p>只需指定解释对象；金额与证据由后端按当前组织生成，不接受客户端伪造。</p>
        </div>
        {!preview && (
          <div className="v3-filters">
            <label>对象类型
              <Select value={subjectType} onChange={setSubjectType} aria-label="解释对象类型" size="small" style={{ minWidth: 150 }}
                options={ADVISOR_SUBJECT_TYPES.map((t) => ({ value: t, label: t }))} />
            </label>
            <label>对象 ID
              <InputNumber value={subjectId} onChange={(v) => setSubjectId(typeof v === 'number' ? v : null)} min={1} precision={0} aria-label="解释对象 ID" />
            </label>
            <button type="button" className="v3-primary-button" disabled={subjectId === null || request.isPending} onClick={() => subjectId !== null && request.mutate({ subjectType, subjectId })}>请求解释</button>
          </div>
        )}
        {request.isPending && <span role="status">正在创建解释任务…</span>}
      </section>
      {job && <JobDetail job={job} previewMode={Boolean(preview)} retryPending={retry.isPending} onRetry={() => retry.mutate(job.id)} />}
      {!job && !preview && jobId === null && <div className="v3-note">选择解释对象后，在此查看受治理的执行与叙事。</div>}
    </main>
  )
}

function problemText(problem: ProblemDetail): string {
  return (problem as { detail?: string }).detail ?? (problem as { title?: string }).title ?? '请稍后重试。'
}

function ProfileForm(props: { profile: AdvisorProfile; pending: boolean; onSubmit: (input: Parameters<typeof advisorApi.updateProfile>[0]) => void; onCancel: () => void }) {
  const { profile } = props
  const [providerModelId, setProviderModelId] = useState<number | null>(profile.providerModelId)
  const [projectId, setProjectId] = useState<number | null>(profile.projectId)
  const [scopeType, setScopeType] = useState(profile.financialScopeType)
  const [scopeId, setScopeId] = useState<number | null>(profile.financialScopeId)
  const [mode, setMode] = useState(profile.budgetEnforcementMode)
  const valid = providerModelId !== null && projectId !== null && scopeId !== null
  return (
    <div className="v3-filters" style={{ marginTop: 12 }}>
      <label>执行模型 ID<InputNumber value={providerModelId} onChange={(v) => setProviderModelId(typeof v === 'number' ? v : null)} min={1} precision={0} aria-label="执行模型 ID" /></label>
      <label>项目 ID<InputNumber value={projectId} onChange={(v) => setProjectId(typeof v === 'number' ? v : null)} min={1} precision={0} aria-label="项目 ID" /></label>
      <label>财务范围类型
        <Select value={scopeType} onChange={setScopeType} aria-label="财务范围类型" size="small" style={{ minWidth: 140 }}
          options={['PROJECT', 'TEAM', 'COST_CENTER'].map((t) => ({ value: t, label: t }))} />
      </label>
      <label>财务范围 ID<InputNumber value={scopeId} onChange={(v) => setScopeId(typeof v === 'number' ? v : null)} min={1} precision={0} aria-label="财务范围 ID" /></label>
      <label>预算模式
        <Select value={mode} onChange={setMode} aria-label="预算模式" size="small" style={{ minWidth: 130 }}
          options={['REQUIRED', 'OPTIONAL'].map((t) => ({ value: t, label: t }))} />
      </label>
      <span className="v3-govern-actions">
        <button type="button" disabled={!valid || props.pending} onClick={() => valid && props.onSubmit({ providerModelId, projectId, financialScopeType: scopeType, financialScopeId: scopeId, budgetEnforcementMode: mode })}>保存新版本</button>
        <button type="button" onClick={props.onCancel}>取消</button>
      </span>
      {props.pending && <span role="status">正在保存…</span>}
    </div>
  )
}

export function JobDetail(props: { job: AdvisorJob; previewMode: boolean; retryPending: boolean; onRetry: () => void }) {
  const { job } = props
  const failed = job.status === 'FAILED'
  const evidencePath = SUBJECT_EVIDENCE[job.subjectType] ?? '/intelligence/overview'
  const currentIndex = ADVISOR_JOB_ORDER.indexOf(job.status)
  return (
    <section className="v3-section" aria-labelledby="v3-job">
      <div className="v3-section-head">
        <span className="v3-eyebrow">Job #{job.id}</span>
        <h2 id="v3-job">执行与叙事</h2>
      </div>
      <ol className="v3-stepper" aria-label="执行状态">
        {ADVISOR_JOB_ORDER.map((state, i) => (
          <li key={state} aria-current={state === job.status ? 'step' : undefined} data-state={state} data-done={i < currentIndex || job.status === 'COMPLETED'} data-active={state === job.status}>
            <span className="v3-step-dot" aria-hidden="true" />{state}
          </li>
        ))}
        {failed && <li data-state="FAILED" data-active><span className="v3-step-dot v3-step-failed" aria-hidden="true" />FAILED</li>}
      </ol>
      <dl className="v3-evidence">
        <div><dt>状态</dt><dd><span className={`v3-pill ${JOB_PILL[job.status] ?? 'v3-pill-neutral'}`}>{job.status}</span>{job.failureCode ? ` · ${job.failureCode}` : ''}</dd></div>
        <div><dt>解释对象</dt><dd>{job.subjectType} #{job.subjectId} · <Link to={evidencePath}>查看确定性证据</Link></dd></div>
        {job.gatewayRequestId !== null && job.gatewayRequestId !== undefined && <div><dt>Gateway 请求</dt><dd>#{job.gatewayRequestId}</dd></div>}
      </dl>
      {job.explanation && (
        <div className="v3-narrative">
          <div className="v3-narrative-facts">
            <span className="v3-pill v3-pill-info">Verified financial facts</span>
            <p>对象 {job.subjectType} #{job.subjectId} 的金额与证据见<Link to={evidencePath}>确定性证据页</Link>；以下叙事未参与任何金额计算。</p>
          </div>
          <div className="v3-narrative-ai">
            <span className="v3-pill v3-pill-neutral">AI-generated explanation</span>
            <p className="v3-narrative-summary">{job.explanation.summary}</p>
            <h3 className="v3-h3">Drivers</h3>
            <p className="v3-narrative-body">{job.explanation.driversExplanation}</p>
            <p className="v3-footnote">AI 未计算任何金额；attempt #{job.explanation.attemptNo} 的叙事已通过输出校验。</p>
          </div>
        </div>
      )}
      {!job.explanation && !failed && <div className="v3-note" role="status">任务{job.status}中，叙事生成后将在此呈现；确定性智能不受影响。</div>}
      {failed && <div className="v3-note" role="alert">任务失败（{job.failureCode ?? '未知原因'}）。确定性智能仍可用；可重试创建新的 append-only attempt，原记录保留。</div>}
      {failed && !props.previewMode && <div className="v3-actions"><button type="button" className="v3-primary-button" disabled={props.retryPending} onClick={props.onRetry}>重试</button></div>}
      {failed && props.previewMode && <div className="v3-note" role="status">预览模式：生产页调用真实重试接口。</div>}
      {props.retryPending && <span role="status">正在重试…</span>}
      <h3 className="v3-h3" style={{ marginTop: 20 }}>Attempt lineage（append-only）</h3>
      {job.attempts.length === 0 && <div className="v3-note">暂无 attempt 记录。</div>}
      {job.attempts.length > 0 && (
        <ul className="v3-list">
          {job.attempts.map((a) => (
            <li key={a.attemptNo} className="v3-row v3-row-slim">
              <div className="v3-row-main"><p className="v3-row-title">Attempt #{a.attemptNo} · {a.status}</p>
                <p className="v3-row-detail">{['Gateway 请求 ' + (a.gatewayRequestId === null || a.gatewayRequestId === undefined ? '—' : `#${a.gatewayRequestId}`)].concat(a.failureCode ? [a.failureCode] : []).join(' · ')}</p>
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
