import './provider.css'

/** Neutral provider lettermark. No third-party logo assets are ever used. */
export function ProviderLettermark(props: { name: string; kind: string }) {
  const initials = props.name.trim().split(/[\s_-]+/).map((w) => w.charAt(0)).join('').slice(0, 2).toUpperCase() || '?'
  const builtin = props.kind === 'BUILTIN'
  return (
    <span className={`v3-lettermark${builtin ? ' v3-lettermark-builtin' : ''}`} aria-hidden="true">{initials}</span>
  )
}

export function KindPill(props: { kind: string }) {
  const builtin = props.kind === 'BUILTIN'
  return <span className={`v3-pill ${builtin ? 'v3-pill-info' : 'v3-pill-neutral'}`}>{builtin ? '内置' : '自定义'}</span>
}

const CONNECTION_PILL: Record<string, string> = { DRAFT: 'v3-pill-neutral', ACTIVE: 'v3-pill-ok', RETIRED: 'v3-pill-neutral' }
const CONNECTION_TEXT: Record<string, string> = { DRAFT: '草稿', ACTIVE: '已启用', RETIRED: '已退役' }

export function ConnectionStatusPill(props: { status: string }) {
  return <span className={`v3-pill ${CONNECTION_PILL[props.status] ?? 'v3-pill-neutral'}`}>{CONNECTION_TEXT[props.status] ?? props.status}</span>
}

export function ProbePill(props: { status: string | null }) {
  if (props.status === 'PASS') return <span className="v3-pill v3-pill-ok">探针通过</span>
  if (props.status === 'FAIL') return <span className="v3-pill v3-pill-risk">探针失败</span>
  return <span className="v3-pill v3-pill-neutral">未探测</span>
}

export function AvailabilityPill(props: { availability: string }) {
  if (props.availability === 'AVAILABLE') return <span className="v3-pill v3-pill-ok">可用</span>
  if (props.availability === 'UNAVAILABLE') return <span className="v3-pill v3-pill-neutral">不可用</span>
  return <span className="v3-pill v3-pill-neutral">{props.availability || '未知'}</span>
}

export function ReadinessDot(props: { ready: boolean | null; label: string }) {
  const cls = props.ready === true ? 'v3-dot-ok' : props.ready === false ? 'v3-dot-bad' : 'v3-dot-unknown'
  return <span className="v3-readiness"><span className={`v3-dot ${cls}`} aria-hidden="true" />{props.label}</span>
}
