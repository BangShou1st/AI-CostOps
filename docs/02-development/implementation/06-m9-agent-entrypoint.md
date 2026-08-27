# M9 Agent Execution Entrypoint

Agentic workers should read, in order:

1. `PROJECT_CONTEXT.md`
2. `docs/superpowers/specs/2026-08-27-v1-to-v2-production-gateway-design.md`
3. `docs/superpowers/plans/2026-08-28-m9-production-foundation-implementation-plan.md`
4. The assigned GitHub Issue / AIC stable ID

Then execute only that issue's task from the implementation plan.

Required development behavior:

```text
TDD / evidence-first
small branch
no unrelated refactor
PowerShell-friendly local commands
full relevant regression before completion claim
CI evidence before merge
```

Never begin M10+ Gateway code while assigned an M9 issue.
