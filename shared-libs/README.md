# Shared library sources

`opencode-zen/` is the in-repository source snapshot of OpenCode Zen shared library version 1.1.1.
Both Maven applications add the autoconfigure module's Java sources and Spring auto-configuration
resources through relative paths in their own `pom.xml` files. They use their existing Spring Boot
starters; the shared Gradle starter is retained in the snapshot but is not resolved as a Maven
artifact because no remote Maven publication is configured.

The project-external workspace master lives at `..\shared-libs\opencode-zen` (one directory up from
this repository). It is authoritative: the snapshot in this repo is a forward copy of it and is never
written back.

## Updating OpenCode Zen (repeatable sync + verify)

Run these steps in order; do not copy in the reverse direction.

1. Update the project-external master (`..\shared-libs\opencode-zen`).
2. Sync the master into this repo: `.\scripts\sync-opencode-shared-libs.ps1`. This copies both module
   directories and their source/resource layout (excluding `.gradle/` and module `build/` outputs)
   into `shared-libs/opencode-zen/`. Existing snapshots are overwritten by the master, never the
   reverse, so uncommitted master edits are protected.
3. Run the shared library's independent Gradle tests from `shared-libs/opencode-zen/`
   (`.\gradlew.bat test`). This compiles and tests the shared source against the declared Spring Boot
   3.3.2 baseline.
4. Run the consuming modules' tests (Backend then Gateway, `./mvnw.cmd -B verify`). These compile the
   same shared source against Spring Boot 4.1.0, verifying Boot 3 / Boot 4 source compatibility.
5. Run the root aggregate gate: `npm run verify`. The `shared-lib tests` step runs the shared Gradle
   tests automatically whenever `shared-libs/opencode-zen/` exists, so the shared suite cannot be
   silently skipped.

Steps 3–5 are not interchangeable: compiling the shared source inside the applications is not the
same as testing the shared library's own Gradle build, and neither is the same as testing a published
Starter JAR (no remote Maven publication is configured, so no published Starter JAR is exercised
here).

