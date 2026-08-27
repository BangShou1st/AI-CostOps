# M9 Review Policy

M9 keeps the repository's established optional-peer-review model.

Mandatory gates:

```text
scope matches assigned AIC issue
targeted evidence/test exists
relevant regression passes
required CI passes
no secret/raw Provider data committed
acceptance evidence is honest and reproducible
```

Peer review is recommended for:

```text
security boundary changes
backup/restore scripts
production config guards
financial/audit behavior
benchmark-driven production SQL/index changes
```

Peer approval is not a global merge gate unless repository settings are intentionally changed in a separate governance decision.
