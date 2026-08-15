# Authentication local development

The `dev` Spring profile enables a file-backed password-reset mailbox. It is a
local delivery sink only: reset tokens are not returned by the forgot-password
API and are not written to normal application logs.

With the standard Compose stack, request a reset through the frontend proxy:

```powershell
Invoke-RestMethod -Method Post `
  -Uri http://localhost:8080/api/v1/auth/password/forgot `
  -ContentType application/json `
  -Body '{"email":"developer@example.test"}'
```

Read the generated reset link from the backend's development mailbox:

```bash
docker compose exec backend sh -c 'cat /var/lib/aicostops/mailbox/*.txt'
```

Each message contains `email=...` and a usable
`resetLink=http://localhost:8080/reset-password?token=...`. The mailbox lives in
the `dev-mailbox-data` Docker volume and is mounted only into the backend. The
backend itself is not published to the host; Nginx remains the HTTP entry point.

In Daily Development Mode (backend on the Windows host with the `local`
profile), the same mailbox is written to `backend/.local-dev/mailbox`
(`application-local.yml` keeps the application.yml default). Request the reset
through the Vite dev server:

```powershell
Invoke-RestMethod -Method Post `
  -Uri http://localhost:5173/api/v1/auth/password/forgot `
  -ContentType application/json `
  -Body '{"email":"developer@example.test"}'
```

Then read the reset link from the host mailbox:

```powershell
Get-ChildItem backend/.local-dev/mailbox/*.txt | ForEach-Object { Get-Content $_.FullName }
```

The invitation mailbox mirrors this: `backend/.local-dev/invitations` on the
host, `/var/lib/aicostops/invitations` in Compose (`dev-invitation-mailbox-data`
volume).

The default/production profile does not enable this sink. Its delivery port is
intentionally not connected to an email provider yet, so production deployment
must supply that integration before password-reset delivery is considered
operational.
