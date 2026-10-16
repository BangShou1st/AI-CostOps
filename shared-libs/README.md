# Shared library sources

`opencode-zen/` is the in-repository source snapshot of OpenCode Zen shared library version 1.1.1.
Both Maven applications add the autoconfigure module's Java sources and Spring auto-configuration
resources through relative paths in their own `pom.xml` files. They use their existing Spring Boot
starters; the shared Gradle starter is retained in the snapshot but is not resolved as a Maven
artifact because no remote Maven publication is configured.

## Updating OpenCode Zen

1. Copy the current shared library source into `opencode-zen/`, preserving both module directories
   and their source/resource layout. Do not copy `.gradle/` or module `build/` outputs.
2. Review the shared version, Java level, Spring Boot dependencies, protocol policies, and any new
   third-party source dependencies. Update the two application POMs only if their source paths or
   compile dependencies changed.
3. From each module directory (`backend/` and then `gateway/`), run
   `./mvnw -B -DskipTests compile` (or `mvnw.cmd` on Windows). These builds verify the shared
   source against the applications' managed dependency versions without calling OpenCode.
