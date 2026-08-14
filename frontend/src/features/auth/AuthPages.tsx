import { useState, type FormEvent, type ReactNode } from 'react'
import { Link, Navigate, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { toProblemDetail } from '../../api/problem'
import { authApi } from './authApi'
import { useAuth } from './AuthSessionProvider'

function AuthLayout({ title, children }: { title: string; children: ReactNode }) {
  return <main className="auth-page"><section className="auth-card"><p className="eyebrow">AI CostOps</p><h1>{title}</h1>{children}</section></main>
}
function ErrorMessage({ error }: { error: unknown }) {
  if (!error) return null
  const problem = toProblemDetail(error)
  return <p className="form-error" role="alert">{problem.detail || problem.title}</p>
}
export function LoginPage() {
  const auth = useAuth(); const navigate = useNavigate(); const [email, setEmail] = useState(''); const [password, setPassword] = useState('')
  const [error, setError] = useState<unknown>(); const [submitting, setSubmitting] = useState(false)
  async function submit(event: FormEvent) { event.preventDefault(); setSubmitting(true); setError(undefined)
    try { await auth.login(email, password); navigate('/app', { replace: true }) } catch (caught) { setError(caught) } finally { setSubmitting(false) } }
  return <AuthLayout title="登录"><form onSubmit={submit}><label>邮箱<input required type="email" value={email} onChange={(e) => setEmail(e.target.value)} /></label>
    <label>密码<input required type="password" value={password} onChange={(e) => setPassword(e.target.value)} /></label><ErrorMessage error={error} />
    <button disabled={submitting}>{submitting ? '正在登录…' : '登录'}</button></form><nav><Link to="/forgot-password">忘记密码？</Link><Link to="/register">创建账号</Link></nav></AuthLayout>
}
export function RegisterPage() {
  const navigate = useNavigate(); const [values, setValues] = useState({ email: '', displayName: '', password: '' }); const [error, setError] = useState<unknown>(); const [submitting, setSubmitting] = useState(false)
  async function submit(event: FormEvent) { event.preventDefault(); setSubmitting(true); setError(undefined)
    try { await authApi.register(values.email, values.displayName, values.password); navigate('/login', { replace: true }) } catch (caught) { setError(caught) } finally { setSubmitting(false) } }
  return <AuthLayout title="创建账号"><form onSubmit={submit}><label>姓名<input required value={values.displayName} onChange={(e) => setValues({ ...values, displayName: e.target.value })} /></label>
    <label>邮箱<input required type="email" value={values.email} onChange={(e) => setValues({ ...values, email: e.target.value })} /></label><label>密码<input required minLength={8} type="password" value={values.password} onChange={(e) => setValues({ ...values, password: e.target.value })} /></label>
    <ErrorMessage error={error} /><button disabled={submitting}>{submitting ? '正在创建…' : '创建账号'}</button></form><Link to="/login">返回登录</Link></AuthLayout>
}
export function ForgotPasswordPage() {
  const [email, setEmail] = useState(''); const [sent, setSent] = useState(false); const [error, setError] = useState<unknown>(); const [submitting, setSubmitting] = useState(false)
  async function submit(event: FormEvent) { event.preventDefault(); setSubmitting(true); setError(undefined); try { await authApi.forgotPassword(email); setSent(true) } catch (caught) { setError(caught) } finally { setSubmitting(false) } }
  return <AuthLayout title="重置密码">{sent ? <p role="status">如果该账号存在，重置说明已发送。</p> : <form onSubmit={submit}><label>邮箱<input required type="email" value={email} onChange={(e) => setEmail(e.target.value)} /></label><ErrorMessage error={error} /><button disabled={submitting}>{submitting ? '正在提交…' : '发送说明'}</button></form>}<Link to="/login">返回登录</Link></AuthLayout>
}
export function ResetPasswordPage() {
  const [params] = useSearchParams(); const navigate = useNavigate(); const [password, setPassword] = useState(''); const [error, setError] = useState<unknown>(); const [submitting, setSubmitting] = useState(false); const token = params.get('token') ?? ''
  async function submit(event: FormEvent) { event.preventDefault(); setSubmitting(true); setError(undefined); try { await authApi.resetPassword(token, password); navigate('/login', { replace: true }) } catch (caught) { setError(caught) } finally { setSubmitting(false) } }
  return <AuthLayout title="设置新密码"><form onSubmit={submit}><label>新密码<input required minLength={8} type="password" value={password} onChange={(e) => setPassword(e.target.value)} /></label><ErrorMessage error={error} /><button disabled={submitting || !token}>{submitting ? '正在更新…' : '更新密码'}</button></form></AuthLayout>
}
export function InvitationPage() {
  const { token = '' } = useParams(); const navigate = useNavigate(); const [displayName, setDisplayName] = useState(''); const [password, setPassword] = useState(''); const [error, setError] = useState<unknown>(); const [submitting, setSubmitting] = useState(false)
  async function submit(event: FormEvent) { event.preventDefault(); setSubmitting(true); setError(undefined); try { await authApi.acceptInvitation(token, displayName, password); navigate('/login', { replace: true }) } catch (caught) { setError(caught) } finally { setSubmitting(false) } }
  return <AuthLayout title="接受邀请"><form onSubmit={submit}><label>姓名<input required value={displayName} onChange={(e) => setDisplayName(e.target.value)} /></label><label>密码<input required minLength={8} type="password" value={password} onChange={(e) => setPassword(e.target.value)} /></label><ErrorMessage error={error} /><button disabled={submitting}>{submitting ? '正在接受…' : '接受邀请'}</button></form></AuthLayout>
}
export function AppPage() {
  const auth = useAuth(); if (!auth.user) return <Navigate to="/login" replace />
  return <main className="app-shell"><p className="eyebrow">Authenticated workspace</p><h1>AI CostOps</h1><p>Signed in as {auth.user.displayName}</p><button onClick={() => void auth.logout()}>退出登录</button></main>
}
