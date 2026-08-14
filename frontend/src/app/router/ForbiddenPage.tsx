import { SETTINGS_COPY } from '../../features/settings/presentation'

export function ForbiddenPage() {
  return (
    <main className="forbidden-page" role="alert">
      <p className="eyebrow">{SETTINGS_COPY.forbiddenTitle}</p>
      <h1>403</h1>
      <p>{SETTINGS_COPY.forbiddenDetail}</p>
    </main>
  )
}
