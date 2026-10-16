# OpenCode Zen shared libraries

This Gradle build follows the conventional two-module Spring Boot layout:

- opencode-zen-spring-boot-autoconfigure: shared protocol and model policies, Spring Boot auto-configuration, properties, and request factory.
- opencode-zen-spring-boot-starter: dependency aggregation entry point.

The modules use group com.sharedlibs and version 1.1.1. Their published artifacts use the same names as the module directories.

## Shared free-model rules

`OpenCodeZenFreeAdmissionPolicy` is the single source of truth for the reviewed V1 Zen free-model
admission set and predicate. Consumers (Backend probe/promotion gates and the Gateway dispatch
barrier) must each execute this check, but they must not define their own free allow-lists.
`OpenCodeZenFreeModelPolicy` proves free/retired facts and never infers pricing from a `-free` name
suffix; a paid, unknown, retired or unlisted model always fails closed.

## Build

From this directory, run:

    .\gradlew.bat test
    .\gradlew.bat compileJava
    .\gradlew.bat publishToMavenLocal

`compileJava`/`test` compile and test against the declared Spring Boot 3.3.2 baseline. The autoconfigure
module is also consumed as source by Spring Boot 4.1.0 applications, which is verified by those
applications' own builds — not by this Gradle build.

Spec Agent uses this source directly through Gradle composite-build substitution. Other projects can consume the starter from Maven Local or a Maven repository.
