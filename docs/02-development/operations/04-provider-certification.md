# Provider Certification (AIC-082) — Operations Runbook

This runbook drives **one real-but-redacted Provider import certification** for
M9 (AIC-082 / Issue #114). The raw Provider export never enters Git; everything
below keeps the certification report free of keys, emails, raw rows,
account/invoice identifiers, prompts and responses.

## 1. Human prerequisite

One real Provider statement/export must be selected **locally** before the
harness can certify anything. Place the file at:

```text
E:\AI-CostOps\.local-provider-certification\input\
```

or pick an explicit external local path. The file must never be attached to an
Issue/PR, added to Git, or copied into `docs/`.

If no real export is available, stop: a synthetic fixture alone does **not**
satisfy AIC-082. The harness can be safety-tested, but the certification stays
`BLOCKED` until real input exists.

## 2. Input safety gate (enforced by the harness before any read)

`scripts/provider-certification.ps1` fails fast unless:

1. the input file exists;
2. `git ls-files --error-unmatch` shows it is **not** tracked;
3. `git check-ignore` shows it is **ignored**;
4. for input inside the repo, it lives only under
   `.local-provider-certification/input/` (never a repository fixture);
5. only the file size, type and a 12-hex SHA-256 prefix are printed — raw
   contents are never printed or recorded.

`.local-provider-certification/` is ignored by the repo `.gitignore`.

## 3. Find the stack's real API entry point

The Compose stack publishes the **frontend** port and reverse-proxies
`/api/v1` to the backend (the backend port itself is not published to the
host). Determine the published port from `.env`:

```powershell
Get-Content .env | Select-String "FRONTEND_PORT"
```

Use `http://localhost:<FRONTEND_PORT>` (e.g. `18080`) as `-BaseUrl`.
`:8080` may be occupied by an unrelated local project — do not assume the
backend is reachable directly on the host.

## 4. Run the certification

From the repo root, with the Compose stack up:

```powershell
Set-Location "E:\AI-CostOps"
.\scripts\provider-certification.ps1 `
  -Provider mimo `
  -InputPath "E:\AI-CostOps\.local-provider-certification\input\<real-export>.xlsx" `
  -BaseUrl "http://localhost:18080"
```

The harness, in order:

1. **Fail-fast input gate** (section 2).
2. **Source inspection** — reads the workbook in memory and computes only the
   row count and monetary aggregate (MiMo sums the `Consumed Amount` column).
3. **Login** (`POST /api/v1/auth/login`; dev bootstrap identity or
   `-Email`/`-Password`) and reuse or create the provider account for the
   certified provider code.
4. **Upload + process** the real file via `POST /api/v1/provider-imports`
   (multipart `file` / `providerAccountId` / `sourceType`), which runs the real
   provider adapter (inspection → parse → normalize → canonicalize).
5. **Await attempt** terminal state via `GET /api/v1/imports/{importBatchId}`.
6. **Confirm** with an Idempotency-Key
   (`POST /api/v1/imports/{importBatchId}/confirm`).
7. **Canonical reconciliation** — read-only MySQL query scoped to the import
   attempt: `SUM(charge_fact.amount)` joined via
   `raw_provider_record.import_attempt_id`.
8. **Evidence record** written to
   `.local-provider-certification/evidence/provider-certification-<provider>.json`
   (counts, aggregates, fingerprints, numeric ids only).

On full success the harness prints:

```text
REAL_PROVIDER_CERTIFICATION_PASS
```

## 5. Committing the redacted report

A real-run evidence report is written by hand to
`docs/03-acceptance/m9-provider-certification-<provider>.md` (Task 9 filename,
e.g. `m9-provider-certification-mimo.md`). Before committing it, complete the
**human redaction review** (Step 5 of AIC-082): verify line-by-line that there
are no key material, emails, raw rows, account/invoice identifiers, prompts,
responses, or full file hashes.

Commit only the harness, the `.gitignore` entry, this runbook and the redacted
report. Never commit the original export.

## 6. Failing with a real schema gap

If the real file exposes a genuine adapter gap, reproduce it with a **minimal
sanitized fixture** (red/green test) before touching adapter code — no real
values, no real identifiers — then rerun the real certification.
