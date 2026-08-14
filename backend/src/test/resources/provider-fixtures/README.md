# Provider Fixture Provenance

Every fixture under `provider-fixtures/` is generated or written from explicit
frozen schemas. **No real private billing data, API keys, bearer tokens, secrets,
passwords, or unredacted personal billing rows are ever committed to this
repository.** Fixtures use obviously synthetic values (`proj_fake`, `user_fake`,
`sk-SECRET-SENTINEL-DO-NOT-PERSIST`).

## Evidence classes

| Class | Meaning |
| --- | --- |
| `REAL_SCHEMA_SANITIZED` | Structure/headers observed from a real provider export; all values sanitized or synthetic. |
| `OFFICIAL_SCHEMA_SYNTHETIC` | Shape taken from current official provider documentation/API reference; values fully synthetic. |
| `SCHEMA_DRIFT_SYNTHETIC` | Intentional drift fixtures used to prove WARN/ERROR policy. |

## Inventory

### `openai/official-usage-completions.json` — `OFFICIAL_SCHEMA_SYNTHETIC`

Synthetic page in the current official OpenAI Organization Usage API shape
(completions usage). Cited reference (verified 2026-08-14):
`https://platform.openai.com/docs/api-reference/usage/audio_transcriptions_object`
(Organization completions usage).

Result fields are limited to the current evidence-backed set:

`input_tokens`, `output_tokens`, `input_cached_tokens`, `input_audio_tokens`,
`output_audio_tokens`, `num_model_requests`, `project_id`, `user_id`,
`api_key_id`, `model`, `batch`, `service_tier`.

### `openai/official-costs.json` — `OFFICIAL_SCHEMA_SYNTHETIC`

Synthetic page in the current official OpenAI Organization Costs API shape.

The current official Costs contract (re-verified 2026-08-14) uses only:

`amount.value`, `amount.currency`, `line_item`, `project_id`.

This fixture deliberately omits the stale assumptions found in older repository
research (`api_key_id` and `quantity` were NOT listed as fields of the current
`organization.costs.result` contract and are not used here).

## Test-generated fixtures

All ZIP/XLSX fixtures used by provider adapter tests are generated in memory by
`ProviderFixtureFactory` from the frozen observed headers recorded in
`docs/01-blueprint/research/01-research-baseline.md`; they are labeled by the
same evidence classes in their test methods.
