import { Empty } from 'antd'
import { Link } from 'react-router-dom'
import './v3-tokens.css'

/** Governed empty state for V3 screens landing after the Overview benchmark. */
export function V3ComingSoon(props: { eyebrow: string; title: string; description: string }) {
  return (
    <main className="v3-page">
      <span className="v3-eyebrow">{props.eyebrow}</span>
      <h1>{props.title}</h1>
      <p className="v3-lede">{props.description}</p>
      <div style={{ marginTop: 20 }}>
        <Empty description="本屏随 M19 第二批交付，先回总览查看已就绪的智能证据">
          <Link to="/intelligence/overview">回成本智能总览</Link>
        </Empty>
      </div>
    </main>
  )
}
