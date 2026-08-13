type AuthEventListener = () => void

export class AuthEventBus {
  private readonly listeners = new Set<AuthEventListener>()

  subscribe(listener: AuthEventListener): () => void {
    this.listeners.add(listener)
    return () => {
      this.listeners.delete(listener)
    }
  }

  emit(): void {
    for (const listener of [...this.listeners]) {
      listener()
    }
  }
}

/** Emitted when a request still receives AUTH_SESSION_EXPIRED after the single-flight refresh. */
export const authEvents = new AuthEventBus()
