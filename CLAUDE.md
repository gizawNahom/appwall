# AppWall

## Development Paradigm

This project follows the **functional programming** paradigm: a functional core with an imperative shell. Decisions and calculations are pure functions over immutable data in the `:core` module (no Android); Android and IO code stays in a thin shell in `:app`.

## Ground rules

- **A blocked app never gets network.** This outranks every other concern. Never close the tunnel on an error path.
- **Dependency-free.** Plain Android framework only: no Jetpack/AndroidX, Room, coroutines or third-party libraries. Test-only exceptions: JUnit 4 and sqlite-jdbc, plus JaCoCo (coverage) and PIT (mutation testing of `:core`, run from its jar with no third-party Gradle plugin). CI uses only GitHub's own actions, pinned to commits.
- **SQL must run on SQLite 3.18** (Android 8, minSdk 26), even though JVM tests use a newer SQLite.

## Architecture

The target architecture and its 19-step migration path live in `docs/product/architecture/brief.md` and `docs/feature/app-architecture/feature-delta.md`. These are local only (not committed); follow the steps in order.
