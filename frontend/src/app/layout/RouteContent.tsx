import { Button } from 'antd'
import { Component, Suspense, type PropsWithChildren } from 'react'
import { useLocation } from 'react-router-dom'

class RouteErrorBoundary extends Component<PropsWithChildren, { failed: boolean }> {
  state = { failed: false }

  static getDerivedStateFromError() {
    return { failed: true }
  }

  render() {
    if (this.state.failed) {
      return (
        <section role="alert" style={{ padding: 32 }}>
          <h2>页面加载失败</h2>
          <p>请重新加载页面。你也可以通过侧边栏访问其他页面。</p>
          <Button onClick={() => window.location.reload()}>重新加载页面</Button>
        </section>
      )
    }
    return this.props.children
  }
}

export function RouteContent({ children }: PropsWithChildren) {
  const { pathname } = useLocation()
  return (
    <RouteErrorBoundary key={pathname}>
      <Suspense fallback={<div role="status" style={{ padding: 48 }}>正在加载页面…</div>}>
        {children}
      </Suspense>
    </RouteErrorBoundary>
  )
}
