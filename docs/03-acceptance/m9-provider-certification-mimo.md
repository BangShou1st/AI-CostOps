# M9 Provider Certification — MiMo (AIC-082)

> One real-but-redacted Provider import certification driven through the real
> running stack. The raw source export never entered Git; only the redacted
> evidence below is committed. This document underwent the human redaction
> review (no API keys, emails, raw rows, account/invoice identifiers, prompts
> or responses).

## What was certified

The real MiMo usage workbook (`Model usage detail` + `Plugin usage detail`
sheets) was uploaded through the live local stack's real import pipeline and
processed by the real MiMo provider adapter.

| Field | Value |
|---|---|
| Provider | `mimo` (`mimo.usage-workbook.v1`) |
| Parser version | `mimo-provider-import-v1` |
| Schema fingerprint | `87f918e1f721d300668d42d38354e4ea0355f724e0d97eaf5edc11bf37061c4d` |
| Detected provider code | `MIMO` |
| Import batch id | `31` (numeric local identifier; evidence correlation only) |
| Latest attempt id | `32` |
| Attempt status | `SUCCEEDED` (error_count=0, warning_count=1) |
| Execution timestamp (UTC) | `2026-09-01T14:53:51Z` |
| Tested implementation SHA (git HEAD at run) | `181f31e180f73cff0538186910a07d0940c9302b` |
| Real input | YES |
| Input tracked by Git | NO |
| Input ignored by Git | YES |

## Execution environment

- Local docker-compose stack, project `ai-costops`; API reached through the
  published frontend entry point (nginx reverse proxy).
- Backend image `ai-costops-backend:local` (built `2026-08-28`); the MiMo
  provider adapter is identical in that build and in this checkout — no
  commits touched `backend/src/main/java/com/aicostops/ingestion/providers/`
  between the image build and `e4b0c1b`.
- The raw input file (MiMo usage workbook export, `.xlsx`, 4770 bytes,
  sha-256 prefix `fea60b949fe2`) lived only under
  `.local-provider-certification/input/` and is ignored/untracked.

## Schema, source and canonical counts

| Metric | Value |
|---|---|
| Source rows (Model sheet data rows with parseable Consumed Amount) | 7 |
| `raw_provider_record.records_seen` | 7 |
| `records_valid` | 7 |
| Warning count | 1 |
| Error count | 0 |
| Canonical `charge_fact` rows (this attempt) | 7 |
| `consumption_fact` (tokens / requests) | recorded by adapter (this attempt) |

## Monetary reconciliation

| Field | Value |
|---|---|
| Source monetary aggregate (Σ Consumed Amount, source currency) | `9.151267` CNY |
| Canonical monetary aggregate (Σ charge_fact.amount, this attempt) | `9.151267` |
| Difference | `0.000000` |

The adapter's documented semantics map each Model row's `Consumed Amount` to
one `charge_fact.reportedAmount`; the canonical aggregate equals the source
statement total exactly (difference 0.000000).

## Warnings

- `EMPTY_OPTIONAL_SHEET` (WARN, ×1): the optional `Plugin usage detail` sheet
  is present but empty. The adapter explicitly recognizes this observed empty
  state as valid and produces no fabricated records.

## Redaction checks

| Check | Result |
|---|---|
| API keys included | NO |
| Credentials / tokens included | NO |
| Emails included | NO |
| Raw source rows included | NO |
| Account / invoice identifiers included | NO |
| Prompts / responses included | NO |
| Full file SHA-256 (only a 12-hex prefix recorded) | not recorded in full |

## Result

```text
REAL_PROVIDER_CERTIFICATION_PASS
```

The real MiMo export authenticated, uploaded, parsed, canonicalized,
reconciled (difference 0.000000) and confirmed successfully through the real
running import pipeline. No adapter schema gap was found, so no sanitized
regression fixture or adapter change was introduced.
