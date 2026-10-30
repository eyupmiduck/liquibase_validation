# ADR 0004: Publish shared test support as a `tests` classifier

- **Status:** Accepted
- **Date:** 2026-10-04
- **Bead:** dml-on2.1 (epic dml-on2, tracked in `dml_utils`)

## Context

`dml_utils` and `ddl_utils` both depend on `liquibase-validation` for the
validation logic, but each carries a near-duplicate copy of the test scaffolding
that wraps it: a shared PostgreSQL Testcontainers base (container, migrated
template database, per-class cloned database, connection/introspection/SQLSTATE
helpers) and a classpath changelog resolver, plus thin tests over
`ChangelogValidator`, `PlpgsqlCheck`, `AuditColumnsCheck` and the linter. The
goal is one shared implementation in `liquibase-validation`.

`liquibase-validation` is a single-module Maven project targeting Java 25. It
publishes immutable releases to GitHub Packages from `v*` tags and has exactly
one runtime dependency, `snakeyaml`; everything else (JUnit, Testcontainers, the
PostgreSQL driver, Jackson, the SARIF schema validator) is test-scoped. The
JaCoCo gate enforces 0.90 line / 0.80 branch coverage over the bundle. The
public surface is `ChangelogValidator`, `PlpgsqlCheck`, `AuditColumnsCheck` and
the `linter` package. ADR 0002 established a preference for keeping one artifact
and adding no runtime dependencies.

The scaffolding needs JUnit 5, Testcontainers (`postgresql`), jOOQ (`DSLContext`), `liquibase-core` and the PostgreSQL
driver. Both consumers
already declare all of these at test scope.

## Decision

Publish the shared test support as a **`tests` classifier of the existing
`liquibase-validation` artifact**, built from `src/test/java` with the
`maven-jar-plugin:test-jar` goal and restricted with `<includes>` to a single
package:

- Package: `io.github.eyupmiduck.changelogvalidator.testing`
  (`ChangelogTestSupport`, `PostgresTestBase`, changelog/routine assertion
  helpers). The library's own tests may use it too.
- `<includes>` limits the `tests` jar to that package, so the library's own test
  classes are not shipped.
- Consumers declare the dependency with `<type>test-jar</type>`:

  ```xml
  <dependency>
      <groupId>io.github.eyupmiduck</groupId>
      <artifactId>liquibase-validation</artifactId>
      <version>${liquibase-validation.version}</version>
      <type>test-jar</type>
      <scope>test</scope>
  </dependency>
  ```

- The support is parameterized by the consumer (schema prefix, owner/test roles,
  changelog resource, container image property); project-specific helpers stay
  in each consumer.

## Alternatives considered

- **A new `liquibase-validation-test` module.** Cleaner separation and explicit
  dependencies, but turns a single-module build into a multi-module one, adds
  release/version coordination, and gives each consumer a second dependency —
  the same trade-off ADR 0002 rejected. Rejected for v1.
- **Put the support in `src/main/java` (`...changelogvalidator.testing`) with
  JUnit/Testcontainers/jOOQ declared `provided` or `optional`.** Keeps one
  artifact and one build, but pollutes the production POM with test frameworks
  and pulls the support classes into the JaCoCo coverage gate, where container
  startup and catalog probes are impractical to cover. Rejected.
- **Vendor the base class into each consumer.** Keeps the library free of test
  concerns, but is exactly the duplication this work removes. Rejected.

## Consequences

Positive:

- One artifact and one build to maintain; no new runtime dependencies and no
  change to the release workflow (`mvn deploy` attaches the `tests` jar
  automatically).
- A `tests` jar built from test sources is outside JaCoCo's default
  `classDirectories` (main output), so the coverage gate still guards production
  code only.
- Consumers are unchanged in dependency shape: they already carry the required
  test-scoped frameworks.

Negative / risks:

- Test-jar dependencies are not transitive, so each consumer must declare
  JUnit/Testcontainers/jOOQ/`liquibase-core` itself. Both already do; this is a
  documented precondition for the shared base.
- The `tests` jar is tied to the library's release cadence, so a scaffolding
  change needs a `liquibase-validation` release (accepted, as with the rules in
  ADR 0002).
- A classifier jar is slightly less discoverable than a named module; the README
  must document the snippet.

## Update (1.7.0): contract classes and one configuration value

Sharing the assertion helpers still left each consumer with near-identical
test classes: the changelog linter test, the naming/orphan checks, the
tracking-table check, the static-analysis check and the previous-release
upgrade test. The upgrade test additionally repeated every container accessor,
which the consumers' own comments flagged as a drift risk, because the regular
test base and the upgrade test are separate classes.

Decision:

- The `tests` jar also ships abstract **contract** classes in the `testing`
  package (currently `ChangelogLinterContract`). A contract holds the `@Test`
  methods; a consumer subclasses it and supplies only the project values
  through `protected` hooks. Contract classes are named `*Contract`, so the
  existing `**/*Test.class` / `**/Test*.class` excludes still keep the
  library's own autotests out of the jar. The library runs each contract
  against its fixture changelog (`ChangelogLinterContractTest`).
- `PostgresTestBase` gains a nested `Configuration` record and a
  `protected Configuration configuration()` accessor. Its default derives the
  value from the existing accessors, so a subclass that overrides the accessors
  keeps working; a consumer that also has an `UpgradeTestBase` subclass
  overrides `configuration()` once with a shared constant instead. All internal
  uses (template database, connections, changelog resources, tracking-table
  names) read the configuration, so the two classes cannot disagree.

Consequences:

- A consumer's linter test is one line and always lints with its own
  `.liquibase-linter.yml` and whitelist, mirroring the verify gate, instead of
  re-implementing the rule wiring with a hardcoded PostgreSQL version.
- The contract is versioned with the library: changing a shared test changes
  what consumer builds enforce on their next dependency bump. This is the same
  trade-off as the shared base and is why the contracts stay small and each one
  has a test in the library.
- `ChangelogLinterContract` reads its config and whitelist relative to the
  module directory (the surefire working directory), matching the paths the
  Maven exec plugin passes to the gate. A project that runs tests from another
  directory overrides `configFile()` / `whitelistFile()`.

## Revisit triggers

- A third consumer appears that does not use Testcontainers or jOOQ, making the
  test-jar contract too heavy.
- The support needs a dependency the library must not expose at all, or the
  single-module build otherwise becomes a problem.
- Consumers need to version the scaffolding independently of the library.

## References

- Epic: bead `dml-on2`; this decision: `dml-on2.1`.
- Precedent: [ADR 0002](0002-linter-packaging-and-consumption.md) (single
  artifact, no new runtime dependencies).
