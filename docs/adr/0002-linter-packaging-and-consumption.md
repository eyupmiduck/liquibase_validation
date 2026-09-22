# ADR 0002: Packaging and consumer integration for the changelog linter

- **Status:** Accepted
- **Date:** 2026-09-21
- **Bead:** ddl-w8y.3 (linter epic ddl-w8y, tracked in `ddl_utils`)

## Context

The changelog linter (tokenizer + rule engine + config/whitelist, see ADR 0001)
needs a home and a consumption path. `liquibase-validation` is a single-module
Maven project targeting Java 25, with `snakeyaml` already a dependency and no
other runtime dependencies. It publishes immutable releases to GitHub Packages
from `v*` tags, and `ddl_utils` currently consumes it at test scope for
`ChangelogValidator`, `PlpgsqlCheck` and `AuditColumnsCheck`.

Decisions to make: artifact/module layout, package names, how the CLI is
distributed, how `ddl_utils` runs the linter, and how rules are versioned.

## Decision

1. **Single artifact.** Keep
   `io.github.eyupmiduck:liquibase-validation` as one artifact and add the
   linter under `io.github.eyupmiduck.changelogvalidator.linter` (subpackages
   `.lexer`, `.rules`, `.config`, `.cli`). Reuse `ChangelogValidator` for the
   changelog graph and the existing `snakeyaml` for YAML. Add **no new runtime
   dependencies**; hand-roll argument parsing for the small flag set (`--config`, `--whitelist`, `--fail-on`,
   `--reporter`).

2. **CLI in the same artifact, distributed as a release asset.** Provide a
   `LinterCli` main class. The release workflow builds a shaded, executable jar (`maven-shade-plugin`, classifier `cli`,
   `Main-Class` set) and attaches it to
   the GitHub Release, so external users can download it without GitHub Packages
   authentication. The library API remains the primary interface.

3. **`ddl_utils` runs the linter as a `verify` gate via the CLI.** Bind
   `exec-maven-plugin` (`exec:java`, `LinterCli` on the classpath) in `verify`
   with configuration and whitelist defaulting to the module base dir (`ddl_utils/.liquibase-linter.yml`,
   `ddl_utils/.liquibase-linter-whitelist.yml`), `--fail-on error`, and the tty
   reporter; CI adds the SARIF reporter and uploads to code scanning. The
   existing JUnit checks (`ChangelogNamingTest`, `ChangelogSqlFilesTest`,
   `PlpgsqlCheckTest`, `AuditColumnsTest`) stay as they are.

4. **Rules version in lockstep with the artifact.** The engine and rule set ship
   with `liquibase-validation` and share its version; consumers pin exact
   versions and absorb accepted violations through the per-module whitelist. No
   separate rule-set artifact in v1.

5. **Release flow is unchanged** (bump the POM, merge, tag `vX.Y.Z`); the
   workflow additionally publishes the CLI jar asset.

## Alternatives considered

- **Separate `liquibase-linter` module/artifact.** Cleaner boundary and
  independent rule versioning, but it turns a single-module build into a
  multi-module one, adds release/version coordination, and gives `ddl_utils` two
  dependencies. Rejected for v1; see revisit triggers.
- **A `liquibase-validation-maven-plugin`.** The most ergonomic option for
  Maven consumers like `ddl_utils`, but Maven-only and more build machinery.
  Deferred until the CLI proves insufficient.
- **JUnit-test-only integration in `ddl_utils`.** Simplest and consistent with
  the existing checks, but it would not exercise the CLI/SARIF path downstream
  users need, and would duplicate config/whitelist plumbing in test code. The
  CLI is dogfooded instead; the library API remains available for rule-level
  tests.
- **Shelling out to a separately installed CLI.** Rejected: environment setup
  and version drift for contributors and CI.

## Consequences

Positive:

- One dependency for consumers; the changelog traversal is reused rather than
  duplicated.
- `ddl_utils` dogfoods the same CLI and SARIF path downstream users get.
- No new runtime dependencies; the CLI is a thin shell over the library.

Negative / risks:

- The core artifact's public surface grows beyond `ChangelogValidator`.
- CLI/shading configuration lives in the library module.
- `exec:java` configuration in `ddl_utils` is more verbose than a JUnit test,
  and its classpath scope must include the linter dependency.

## Revisit triggers

- Rule changes start shipping independently of the traversal helpers.
- The CLI grows enough to want a real argument-parsing dependency (`picocli`)
  or its own module and distribution.
- The core API must stay minimal for existing consumers.
- A non-Maven or non-JVM consumer needs a distribution form we do not publish.

## References

- Linter epic: bead `ddl-w8y`; this decision: `ddl-w8y.3`.
- CLI and consumption beads: `ddl-w8y.18`, `ddl-w8y.19`.
- ADR 0001: hand-written PostgreSQL lexer.
