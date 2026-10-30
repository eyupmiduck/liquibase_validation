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
  `**/*Test.class` / `**/*Tests.class` / `**/*Test$*.class` excludes keep the
  library's own autotests and their nested probes out of the jar without
  catching a support class such as `TestResources` (the old, broader
  `**/Test*.class` pattern did). The library runs each contract against its
  fixture changelog (`ChangelogLinterContractTest`).
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

## Update (1.8.0): the remaining duplicated tests and generic helpers

The 1.7.0 contracts covered the linter test and the upgrade-test accessors. The
remaining duplication was structural rather than incidental:

- four near-identical test classes (`ChangelogNamingTest`,
  `ChangelogSqlFilesTest`, `LiquibaseTrackingTablesTest`, `PlpgsqlCheckTest`);
- the ddl-side routine/rollback signature check, which dml only approximated
  with a hand-maintained list of files;
- a private classpath resource reader in each consumer;
- roughly 400 lines of generic PostgreSQL helpers (lock plumbing, catalog
  introspection, table create/drop) stranded in ddl_utils' local
  `PostgresTestBase`, although nothing about them is ddl-specific.

Decision:

- Add four contracts in the `testing` package:
  `ChangelogNamingContract`, `ChangelogSqlFilesContract`,
  `TrackingTablesContract` and `PlpgsqlCheckContract`. The last two extend
  `PostgresTestBase`; their hooks supply the application or routine schemas.
  Because the contract, not the consumer's own test base, is the direct
  superclass of the consumer's test class, those two contracts also require the
  consumer's `configuration()` explicitly, so the custom roles, image and
  changelog cannot silently fall back to the shared defaults.
- Add `Configuration.extensionInstaller`, a hook that installs the image's
  extensions and grants into a database before the changelog runs. The default
  `PostgresTestBase.installExtensions` runs it, so a consumer that shares one
  configuration (its test base, its contract tests and its upgrade test) also
  shares its extension setup, and the upgrade test's scratch database gets the
  same extensions as the template.
- Add `TestResources` for classpath reads (`read`, `readSqlFiles`), used by
  `GrantAssertions` and by the consumer tests that execute a rollback script. It
  reads an exploded classpath and a jar, like the old ddl-side reader.
- Add `RoutineAssertions.assertRollbackDropsMatchRoutines`: it collects every
  rollback `DROP ... IF EXISTS <signature>` from the given classpath
  directories, rejects duplicate signatures, resolves each signature against
  `pg_proc`, and asserts the resolved set equals the routines in the given
  schemas. Both consumers run it.
- Move the generic lock and catalog helpers into `PostgresTestBase`. The
  ddl_utils-specific helpers (lock-settings rows, the temporary NOT NULL proof
  constraint name and its simulated states) stay in the consumer.

Consequences:

- `PlpgsqlCheckContract` cannot run in the library's own build: its stock
  PostgreSQL image has no `plpgsql_check` extension. The library tests the
  contract's defaults; the consumer images, which compile the extension, run the
  inherited analyser test. This is documented on the contract.
- The dml project gains the routine/rollback parity check it lacked, and the
  generic lock helpers are now available to both consumers instead of being
  copied on demand.

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
