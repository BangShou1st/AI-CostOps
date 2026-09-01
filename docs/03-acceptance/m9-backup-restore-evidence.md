# M9 Backup / Restore Evidence — AIC-080

> Real end-to-end restore drill on the local developer stack. The drill is
> non-destructive by construction: it never tears down, prunes or overwrites
> the normal developer volumes; restore happens only inside an isolated
> Compose project with its own network, volumes and ports, and cleanup removes
> only what the drill created.

## Scope

- `scripts/ops/backup-mysql.ps1` — logical `mysqldump` of the running MySQL
  service with a SHA-256 sidecar, written under `.local-backups/mysql/`.
- `scripts/ops/backup-evidence.ps1` — read-only mirror of the Evidence bucket
  via a disposable `minio/mc` container into `.local-backups/evidence/`, with
  a per-file SHA-256 manifest.
- `scripts/ops/restore-mysql.ps1` — restore a dump into an ISOLATED project
  (sidecar-verified, TCP credential path that matches the `%`-host app user).
- `scripts/ops/restore-evidence.ps1` — mirror evidence objects into the
  isolated bucket and byte-for-byte hash-verify every object against the
  manifest.
- `scripts/ops/restore-drill.ps1` — one-command end-to-end drill:
  readiness → synthetic data via the public API → backup → isolated restore →
  restart backend/frontend → verification → cleanup → `M9_RESTORE_DRILL_PASS`.

- Branch: `feat/m9-backup-restore`
- Tested implementation SHA: `cbb84b586c2f418d34a17e9cd87466529954dc27` (`fix(ops): harden restore drill verification and proxy handling`)
- Evidence SHA: this commit.

## Last full drill run (PASS, M9_RESTORE_DRILL_PASS)

Command (from repo root, with the normal `ai-costops` stack running):

```powershell
Set-Location "E:\AI-CostOps"
.\scripts\opsestore-drill.ps1 -EnvFile .env -SourceProject ai-costops
```

Result (full log: `drill_run_20260901_6.log`, backup dirs `20260901110825`):

```text
[PASS] source stack healthy (ai-costops)
[PASS] synthetic provider import confirmed (id 30)
[PASS] synthetic expense approved, allocated, posted (id 35)
[INFO] source counts: charges=30 expenses=35 postings=20 entries=21 evidence=1 period=2/OPEN
[PASS] MySQL backup written to .local-backups\mysql60901110825 (sha256 d1757226...)
  dump bytes: 436348, elapsed: 0.832s
[PASS] Evidence bucket mirrored to .local-backups\evidence60901110825 (50 files, 1.127s)
[INFO] derived frontend container port=80 (from nginx/compose, compatible with #121's 8080 via derivation)
[PASS] isolated stores up
[PASS] SHA-256 verified: d1757226e64bb594bc3baa32c1a177b19425d54258fc71ad8f9e2d1cbe4ae5f8
[PASS] Restore into aicostops-restore-drill-20260901110825 completed: 46 tables, 5.065s
[PASS] Restored Evidence bucket 'aicostops-evidence': 50/50 objects hash-verified
[PASS] login works on the restored stack
[PASS] charges count: 30 == 30
[PASS] expenses count: 35 == 35
[PASS] ledger postings count: 20 == 20
[PASS] ledger entries count: 21 == 21
[INFO] restored Evidence bucket object count: 50 (backup manifest count: 50)
[PASS] evidence objects count: 50 == 50
[PASS] evidence download content exact (synthetic receipt hash match)
[PASS] period state: OPEN id=2 preserved after restore (string-compared, strict-mode safe)
[PASS] isolated drill project removed; normal developer volumes untouched
M9_RESTORE_DRILL_PASS
```

Timings (engineering evidence, not RPO/RTO promises):

```text
backup elapsed: 2.4s
restore elapsed: 39.8s
verify elapsed: 170.3s
total elapsed: 661.3s
```

Cross-PR compatibility:

```text
#121 final frontend runtime:   nginx:1.28.3-alpine, USER nginx (101), listen 8080, compose 8080
#122 drill override:            derived from frontend/nginx/default.conf (listen 80 on this branch, 8080 on #121)
                               — no hardcoded branch-specific port; resolves correctly after merge
```

## Safety boundary verification

- The isolated project used its own network
  `aicostops-restore-drill-20260901110825-network`, its own volumes
  (mysql/redis/minio/dev-mailbox) and its own frontend port (derived via override),
  verified by `docker compose -p aicostops-restore-drill-<ts> down -v` removing only
  drill-created resources (confirmed via `docker volume ls` — no `ai-costops` volumes removed).
- The normal `ai-costops` stack stayed up throughout (all 5 services healthy
  before and after) and its volumes were never listed for removal.
- No `M9_RESTORE_DRILL_PASS_WITH_EVIDENCE_MIRROR_BYPASS` — full MinIO mirror
  and per-object hash verification executed.

## Defects found and fixed during this implementation

1. **MySQL restore Access denied** — the app user is created by the official
   image as `'aicostops'@'%'` (no `'@'localhost'` entry). Fixed via TCP (`-h 127.0.0.1 --protocol=TCP`).
2. **Evidence restore NoSuchBucket / isolation** — `Invoke-Compose` previously
   ignored the `-f` file list, so the isolated project joined the source network.
   Fixed by always passing `-f compose.yaml -f <override>`.
3. **Frontend port overlap** — override mapped to `8080` while image listened on
   `80`; fixed by mapping the actually-listening port (now derived from nginx config
   for cross-PR compatibility with #121's 8080).
4. **Evidence object count drift** — `backup-manifest.json` was mirrored into the
   restored bucket. Fixed by staging only the evidence object tree.
5. **PowerShell 7 response typing** — `Invoke-WebRequest.Content` string vs `byte[]`
   comparison, fixed to handle both.
6. **Evidence count self-comparison** — `Assert-CountsEqual $evidenceBackupCount $evidenceBackupCount`
   always passed; fixed to independently count the restored MinIO bucket
   (`mc ls --json drill/$bucket`) and compare `$evidenceBackupCount == $restoredEvidenceCount`.
7. **Billing period strict-mode + string array expansion** — `Set-StrictMode` flagged
   `$accessKey/$bucket` as unset; billing period `id` is string-serialized and
   `$openPeriod.id` expanded to "2 1" when the API returned multiple OPEN periods.
   Fixed by persisting MinIO coords after backup and by string-safe period id
   comparison with array-unwrap guards.
8. **Localhost proxy hang** — `Invoke-RestMethod`/`Invoke-WebRequest` hung on
   `localhost:18082` due to PowerShell honoring a stale system proxy for loopback.
   Fixed by adding `-NoProxy` to all drill HTTP calls (`Invoke-Json` + `Invoke-WebRequest`).

## RPO / RTO positioning

Engineering objective, not a production promise:

```text
MySQL backup < 5 min (local ~0.8 s at this data size)
Evidence mirror < 5 min (local ~1.1 s at 50 objects)
restore-drill end-to-end < 30 min (local ~11 min total)
```
