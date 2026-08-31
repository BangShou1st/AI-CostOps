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
- Implementation SHA: `ops` commit (feat(ops): add isolated backup and restore drill)
- Evidence SHA: the commit that adds this document.

## Last full drill run (PASS)

Command (from repo root, with the normal `ai-costops` stack running):

```powershell
Set-Location "E:\AI-CostOps"
.\scripts\ops\restore-drill.ps1 -EnvFile .env -SourceProject ai-costops
```

Result (log: `.drill-run16.log`):

```text
[PASS] source stack healthy (ai-costops)
[PASS] synthetic provider import confirmed (id 24)
[PASS] synthetic expense approved, allocated, posted (id 29)
[PASS] MySQL backup written to .local-backups\mysql\20260831223854 (sha256 d56678fb...)
[PASS] Evidence bucket mirrored to .local-backups\evidence\20260831223854 (38 files, 1.018s)
[PASS] isolated stores up
[PASS] SHA-256 verified: d56678fb1dcf3a1801b020254e44c4214930639dfd72369303b421dc2baf5006
[PASS] Restore into aicostops-restore-drill-20260831223854 completed: 46 tables, 5.513s
[PASS] Restored Evidence bucket 'aicostops-evidence': 38/38 objects hash-verified
[PASS] login works on the restored stack
[PASS] charges count: 24 == 24
[PASS] expenses count: 29 == 29
[PASS] ledger postings count: 14 == 14
[PASS] ledger entries count: 15 == 15
[PASS] evidence objects count: 38 == 38
[PASS] isolated drill project removed; normal developer volumes untouched
M9_RESTORE_DRILL_PASS
```

Timings (engineering evidence, not RPO/RTO promises):

```text
backup elapsed: 2.3s
restore elapsed: 40s
verify elapsed: 169.4s
total elapsed: 659.7s
```

## Safety boundary verification

- The isolated project used its own network
  `aicostops-restore-drill-20260831223854-network`, its own volumes
  (mysql/redis/minio/dev-mailbox) and its own frontend port (18082), verified
  by `docker compose -p aicostops-restore-drill-<ts> down -v` removing only
  drill-created resources.
- The normal `ai-costops` stack stayed up throughout (all 5 services healthy)
  and its volumes were never listed for removal by the drill cleanup.

## Defects found and fixed during this implementation

1. **MySQL restore Access denied** — the app user is created by the official
   image as `'aicostops'@'%'` (no `'@'localhost'` entry). The mysql client's
   default socket connection resolves host to `localhost`, so authentication
   failed. Fixed by connecting through TCP (`-h 127.0.0.1 --protocol=TCP`),
   which matches the `%` host.
2. **Evidence restore NoSuchBucket / isolation** — the drill's Compose
   override file (unique network, unique frontend port) was never passed to
   `docker compose` (`Invoke-Compose` ignored the `-f` file list), so the
   isolated project joined the source network, where both MinIO instances
   answered the `minio` service name and DNS round-robin caused intermittent
   `NoSuchBucket`. Fixed by always passing `-f compose.yaml -f <override>`.
3. **Frontend port overlap** — the override initially mapped the drill
   frontend to the container's 8080 while the committed frontend image listens
   on 80; the drill frontend then had no listener and liveness failed. Fixed by
   mapping the actually-listening port (`${DrillFrontendPort}:80`).
4. **Evidence object count drift** — `backup-manifest.json` inside the backup
   directory was mirrored into the restored bucket, making the object count 1
   higher than the manifest. Fixed by staging only the evidence object tree
   (excluding the manifest) before mirroring, and by verifying restored objects
   under the bucket-name prefix that `mc cp` preserves.
5. **PowerShell 7 response typing** — `Invoke-WebRequest.Content` is a string
   for text payloads (not `byte[]`), so the evidence download comparison was
   fixed to handle both forms.

## RPO / RTO positioning

Engineering objective, not a production promise:

```text
MySQL backup < 5 min (local ~1-3 s at this data size)
Evidence mirror < 5 min (local ~1 s)
restore-drill end-to-end < 30 min (local ~11 min total)
```