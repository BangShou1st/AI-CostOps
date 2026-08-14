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

Result fields cover the current completions contract: `object` type marker,
`input_tokens` / `output_tokens` totals, the cached / cache-write / uncached and
text / audio / image breakdown components, `num_model_requests`, and the
`project_id` / `user_id` / `api_key_id` / `model` / `batch` / `service_tier`
dimensions.

Breakdown components are never added to the totals (`input_tokens` stays the
provider aggregate; there is no `inputTokensPlusCached`).

### `openai/official-costs.json` — `OFFICIAL_SCHEMA_SYNTHETIC`

Synthetic page in the current official OpenAI Organization Costs API shape
(verified 2026-08-14). The current official Costs result contract includes:

`amount` (`value` / `currency`), `api_key_id`, `line_item`, `project_id`,
`quantity`, `object`.

- `amount.value` / `amount.currency` are the minimum required money semantics.
- `line_item` / `project_id` / `api_key_id` / `quantity` are optional provider
  dimensions that may be absent when the request does not group by them.
- `api_key_id` is a provider identifier, not secret API key material.
- `quantity` is preserved provider-native; Group 2 assigns no guessed unit.

The fixture also carries the official `object` type markers
(`page` / `bucket` / `organization.costs.result`).

## Test-generated fixtures

All ZIP/XLSX fixtures used by provider adapter tests are generated in memory by
`ProviderFixtureFactory` from the frozen observed headers recorded in
`docs/01-blueprint/research/01-research-baseline.md`; they are labeled by the
same evidence classes in their test methods.
