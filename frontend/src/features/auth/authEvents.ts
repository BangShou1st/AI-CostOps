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

/** Emitted when the session is dead: a retried request still gets AUTH_SESSION_EXPIRED, or the refresh itself returns it. */
export const authEvents = new AuthEventBus()
