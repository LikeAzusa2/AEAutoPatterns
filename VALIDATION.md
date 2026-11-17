# Validation

Environment: Minecraft 1.21.1, NeoForge 21.1.169, AE2 19.2.13, Java 21.

## Automated checks

- `./gradlew build`: compiles the mod and runs 42 JUnit tests for dependency planning, cycle handling, incremental analysis, layout and tree streaming.
- `./gradlew runGameTestServer`: 23 Minecraft GameTests cover terminal persistence, AE pattern encoding and decoding, output capacity, upload routing, recipe selection, and AE network behavior.
- `./gradlew analysisBenchmark`: runs the standalone planner benchmark; results depend on JVM warmup and host load.

The GitHub Actions workflow runs `build` and `runGameTestServer` on pushes and pull requests.

## Manual checks

The client has been launched during development, but the current provider-management layout, JEI ghost-item drag interaction, cable tint changes, and behavior in large third-party modpacks have not received a complete manual acceptance pass. Server-side tests do not validate client rendering or mouse interaction.
