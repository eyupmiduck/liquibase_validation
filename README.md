# liquibase_validation

[![codecov](https://codecov.io/gh/eyupmiduck/liquibase_validation/branch/main/graph/badge.svg)](https://codecov.io/gh/eyupmiduck/liquibase_validation)

Test logic to validate Liquibase changelogs and changesets, and to statically
analyse PL/pgSQL routines with the `plpgsql_check` extension.

## Contents

- `io.github.eyupmiduck.changelogvalidator.ChangelogValidator` — traverses a
  changelog graph from its master file, following nested `<include>` elements,
  and finds invalidly named changeSets and SQL files as well as orphaned SQL
  files. ChangeSet ids must match the `NNN-name` pattern by default, or a
  caller-supplied `Pattern` passed to the overload (for example to accept
  `function-<schema>.<name>` routine changesets). SQL is recognised both in
  `<sqlFile>` elements and as the external body of a
  `<createProcedure>`/`<createFunction>` element; files under a routine
  directory (`functions`, `procedures`, or their `-rollback` variants) are
  exempt from the `NNN-` SQL naming rule.
- `io.github.eyupmiduck.changelogvalidator.PlpgsqlCheck` — runs
  `plpgsql_check` over the PL/pgSQL routines in a set of schemas and validates
  the findings against an allow-list, reporting unexpected findings and stale
  entries. The database must have the `plpgsql_check` extension installed.
- `io.github.eyupmiduck.changelogvalidator.AuditColumnsCheck` — checks the
  audit-column convention on a set of schemas: every base table has
  `created_at` and `updated_at`, both `timestamptz NOT NULL DEFAULT now()`, with
  an enabled `BEFORE UPDATE ... FOR EACH ROW` trigger. A behavioral probe can
  verify on a real row that an `UPDATE` refreshes `updated_at` and preserves
  `created_at`, rolling the change back.
- `io.github.eyupmiduck.changelogvalidator.linter` — a changelog linter: a
  hand-written PostgreSQL tokenizer, Liquibase-compatible statement splitting, a
  changeset model, a rule engine with tty/JSON/SARIF reporters, and rules that
  combine SQL tokens with Liquibase changeset semantics.

## Changelog linter

The linter reads a changelog graph and reports rules that need both the SQL and
the changeset attributes. Built-in rules:

| Rule | Severity | Default | Detects |
| --- | --- | --- | --- |
| `changeset-run-in-transaction-required` | error | on | a transaction-forbidden statement in a changeset without `runInTransaction="false"` |
| `changeset-single-statement` | error | on | a transaction-forbidden statement that is not the only statement in a `runInTransaction="false"` changeset |
| `changeset-prefer-single-statement` | warning | opt-in | several statements in a `runInTransaction="false"` changeset |
| `require-concurrent-index-creation` | warning | opt-in | a `CREATE INDEX` (Squawk) on a table not created in the same changeset |
| `require-concurrent-index-deletion` | warning | opt-in | a `DROP INDEX` (Squawk) |
| `changeset-rollback-required` | warning | opt-in | a changeset with no `<rollback>` |
| `changeset-rollback-parity` | warning | opt-in | a rollback with far fewer statements than the forward SQL |

An opt-in rule runs when its id is listed under `include` in the configuration.
Suppress a finding with a whitelist entry (see below).

Before a rule runs, the SQL is normalised the way Liquibase would execute it:
`${property}` placeholders are substituted, the changeset's `<modifySql>`
transformations are applied (honouring `applyToRollback` and the `dbms` filter),
and the `createTable`/`createIndex`/`dropIndex` change types are rendered to SQL.
The limits of this normalisation are recorded in
[docs/adr/0003-sql-normalisation.md](docs/adr/0003-sql-normalisation.md).

Run it with the executable jar (published as the `cli` classifier and, from
0.15.0, attached to the release), or from the library API:

```sh
liquibase-linter --changelog-root src/main/resources/db/changelog
liquibase-linter --changelog-root src/main/resources/db/changelog --reporter sarif --fail-on warning
```

| Option                     | Default                       | Meaning                              |
|----------------------------|-------------------------------|--------------------------------------|
| `-r, --changelog-root DIR` | required                      | changelog directory                  |
| `-m, --master FILE`        | `DIR/db.changelog-master.xml` | master changelog                     |
| `-c, --config FILE`        | `.liquibase-linter.yml`       | YAML configuration                   |
| `-w, --whitelist FILE`     | `.liquibase-linter-whitelist.yml` | accepted findings                |
| `--reporter`               | `tty`                         | `tty`, `json` or `sarif`             |
| `--fail-on`                | `error`                       | `error`, `warning`, `info` or `none` |

The exit code is `0` when the run passes, `1` when findings reach the `failOn`
threshold (or a whitelist entry is stale), and `2` for a usage or runtime error.

The configuration is a per-module `.liquibase-linter.yml`:

```yaml
pgVersion: '17'
failOn: error
exclude:
  - prefer-bigint-over-int
include:
  - require-concurrent-index-creation
rules:
  changeset-single-statement:
    severity: warning
```

A finding can be accepted with a per-module `.liquibase-linter-whitelist.yml`
(the CLI's `--whitelist`; a missing file accepts nothing):

```yaml
- rule: changeset-single-statement
  file: changes/sql_changes/007-some-index.sql
  changeset: 007-some-index
  statement: CREATE INDEX CONCURRENTLY
  reason: >-
    Intentional: the statement is followed by a precondition comment; the
    statement list is single after Liquibase splitting. See PR #123.
```

Each selector (a rule id, a path suffix of the finding's file, a changeset id,
or the offending statement's label) is optional and matches any value when
omitted, but an entry must set at least one. Matching is fail-closed: a finding
no entry accepts is reported, an entry that matches no finding is stale and
fails the run, and a finding that matches more than one entry is rejected.

### Code scanning

`--reporter sarif` writes a SARIF v2.1.0 document (tool name `liquibase-linter`,
one rule per finding rule id with its default level, and a result with the
mapped level, message/markdown and file/line/column location). A test validates
the document against the SARIF 2.1.0 schema under
`src/test/resources/sarif/sarif-2.1.0.json`, so it cannot drift from the format
GitHub code scanning consumes.

Upload it on a pull request with `github/codeql-action/upload-sarif`, and keep
the CLI's exit code as the gate. The
[`.github/workflows/linter-sarif.yml`](.github/workflows/linter-sarif.yml)
workflow does exactly this against the sample changelog in
`src/test/resources/sarif`: it lints with `--fail-on none` so the SARIF file is
produced and uploaded (findings appear inline, next to CodeQL), then runs again
with `--fail-on warning` to show the failing exit code. The sample deliberately
contains a finding, so that step is advisory (`continue-on-error`); a real
consumer wires the failing run into its gate as in `ddl_utils`. For the upload
the job needs `security-events: write`.

## Using the library

The library is published to GitHub Packages from a `v*` tag (for example
`v0.12.0`), and each artifact version is immutable:

```xml

<dependency>
    <groupId>io.github.eyupmiduck</groupId>
    <artifactId>liquibase-validation</artifactId>
    <version>0.12.0</version>
    <scope>test</scope>
</dependency>
```

Consuming builds must be authenticated to GitHub Packages even for public
packages. Add the repository to the consuming POM:

```xml

<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/eyupmiduck/liquibase_validation</url>
    </repository>
</repositories>
```

And provide credentials in `~/.m2/settings.xml` (a classic personal access
token with the `read:packages` scope is required):

```xml

<settings>
    <servers>
        <server>
            <id>github</id>
            <username>YOUR_GITHUB_USERNAME</username>
            <password>YOUR_CLASSIC_PAT</password>
        </server>
    </servers>
</settings>
```

Before a consuming repository's `GITHUB_TOKEN` can read the package, grant
that repository read access under the package's **Manage Actions access**
settings.

## PL/pgSQL static analysis

`PlpgsqlCheck` runs `plpgsql_check_function_tb(..., all_warnings => true)` over
the routines in the given schemas and matches each finding against an
allow-list, so a build fails on an unexpected finding or a stale entry. A
trigger function is analysed through each relation its trigger is attached to;
an unattached trigger function is skipped, because the analyser needs a trigger
relation to resolve `NEW`/`OLD`.

```java
try(InputStream in = getClass().getClassLoader()
        .getResourceAsStream("plpgsql-check-whitelist.yml")){
List<PlpgsqlCheck.AllowedFinding> allowed = PlpgsqlCheck.loadWhitelist(in);
PlpgsqlCheck.Report report = PlpgsqlCheck.check(connection, List.of("my_schema"), allowed);
// report.unexpected() and report.stale() are empty when the check passes
}
```

The allow-list is YAML; each entry names any of `schema`, `function`, `level`,
`statement` and `message`, and an unset field matches any value:

```yaml
- schema: ddl_utils_lib
  function: alter_table
  level: security
  statement: EXECUTE
  message: text type variable is not sanitized
```

## Audit columns

`AuditColumnsCheck` enforces a project-wide audit-column convention. The catalog
check reports every base table that is missing `created_at`/`updated_at`, has
the wrong type, is nullable, does not default to `now()`, or lacks an enabled
`BEFORE UPDATE ... FOR EACH ROW` trigger:

```java
List<AuditColumnsCheck.Violation> violations =
        AuditColumnsCheck.findViolations(connection, List.of("my_schema"));
// violations is empty when every table complies
```

The behavioral probe verifies the trigger actually works on a real row: it
updates one row's `updated_at` to a sentinel in the past and reads it back, so a
working trigger overwrites the sentinel and leaves `created_at` alone. The
update is rolled back, and a caller's in-flight transaction is preserved (the
probe uses a savepoint when the connection is not in autocommit mode):

```java
AuditColumnsCheck.probeUpdate(connection, "my_schema","my_table")
        .

ifPresent(probe ->{
        // probe.passed() is true when updated_at was refreshed and
        // created_at was preserved
        });
```

An empty table has no row to probe, so the result is empty.

## Releasing

1. Bump `<version>` in `pom.xml` (no `-SNAPSHOT` suffix).
2. Merge to `main`.
3. Push a matching tag, e.g. `git tag v0.12.0 && git push origin v0.12.0`.

The `Publish` workflow fails if the tag does not equal `v` + the POM version.
Consumer POMs must then be updated to the new version explicitly.

## Building

The `PlpgsqlCheck` tests start a PostgreSQL container (and build a small image
with the `plpgsql_check` package), so Docker must be running:

```sh
./mvnw verify
```
