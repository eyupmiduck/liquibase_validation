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
  combine SQL tokens with Liquibase changeset semantics. See the
  [rule reference](#rule-reference), [configuration](#configuration) and
  [tokenizer](#tokenizer) sections.

## Changelog linter

The linter reads a changelog graph and reports rules that need both the SQL and
the changeset attributes. Rules are versioned with the artifact, run in a fixed
order, and are configured per module in `.liquibase-linter.yml`.

Before a rule runs, the SQL is normalised the way Liquibase would execute it:
`${property}` placeholders are substituted, the changeset's `<modifySql>`
transformations are applied (honouring `applyToRollback` and the `dbms` filter),
and the `createTable`/`createIndex`/`dropIndex` change types are rendered to SQL.
The limits of this normalisation are recorded in
[docs/adr/0003-sql-normalisation.md](docs/adr/0003-sql-normalisation.md).

Run it with the executable jar (published to GitHub Packages as the `cli`
classifier alongside the library), or from the library API:

```sh
liquibase-linter --changelog-root src/main/resources/db/changelog
liquibase-linter --changelog-root src/main/resources/db/changelog --reporter sarif --fail-on warning
```

| Option                     | Default                           | Meaning                              |
|----------------------------|-----------------------------------|--------------------------------------|
| `-r, --changelog-root DIR` | required                          | changelog directory                  |
| `-m, --master FILE`        | `DIR/db.changelog-master.xml`     | master changelog                     |
| `-c, --config FILE`        | `.liquibase-linter.yml`           | YAML configuration                   |
| `-w, --whitelist FILE`     | `.liquibase-linter-whitelist.yml` | accepted findings                    |
| `--reporter`               | `tty`                             | `tty`, `json` or `sarif`             |
| `--fail-on`                | `error`                           | `error`, `warning`, `info` or `none` |
| `-h, --help`               |                                   | print usage and exit                 |

The exit code is `0` when the run passes, `1` when findings reach the `failOn`
threshold or a whitelist entry is stale (`0` under `--fail-on none`), and `2`
for a usage or runtime error.

### Rule reference

An **error/warning/info** is the rule's default severity; **on** means it runs by
default, **opt-in** means it runs only when listed under `include`. The table
lists the built-in rules in registration (reporting) order.

| Rule                                                                              | Severity | Default | What it detects                                                                                            |
|-----------------------------------------------------------------------------------|----------|---------|------------------------------------------------------------------------------------------------------------|
| [`changeset-run-in-transaction-required`](#changeset-run-in-transaction-required) | error    | on      | a transaction-forbidden statement in a changeset without `runInTransaction="false"`                        |
| [`changeset-single-statement`](#changeset-single-statement)                       | error    | on      | a transaction-forbidden statement that is not the only statement in a `runInTransaction="false"` changeset |
| [`changeset-prefer-single-statement`](#changeset-prefer-single-statement)         | warning  | opt-in  | several statements in a `runInTransaction="false"` changeset                                               |
| [`require-concurrent-index-creation`](#require-concurrent-index-creation)         | warning  | opt-in  | a `CREATE [UNIQUE] INDEX` on a table not created in the same changeset                                     |
| [`require-concurrent-index-deletion`](#require-concurrent-index-deletion)         | warning  | opt-in  | a `DROP INDEX`                                                                                             |
| [`changeset-rollback-required`](#changeset-rollback-required)                     | warning  | opt-in  | a changeset with no `<rollback>`                                                                           |
| [`changeset-rollback-parity`](#changeset-rollback-parity)                         | warning  | opt-in  | a rollback with far fewer statements than the forward SQL                                                  |
| [`routine-dynamic-sql`](#routine-dynamic-sql)                                     | info     | opt-in  | a routine body that uses `EXECUTE`                                                                         |
| [`block-raw-alter-table`](#block-raw-alter-table)                                 | error    | on      | a raw `ALTER TABLE` in changelog SQL                                                                       |
| [`require-dbms-postgresql`](#require-dbms-postgresql)                             | error    | on      | PostgreSQL-only SQL in a changeset without a `dbms="postgresql"` gate                                      |

Every rule is suppressed the same way: an `exclude` entry turns it off for the
module, a `rules` severity override downgrades it (for example to `info`, which
`--fail-on error` ignores), and a whitelist entry accepts one specific finding.
The per-rule sections give an example of each.

#### changeset-run-in-transaction-required

PostgreSQL rejects several statements inside a transaction block; running one in
a changeset Liquibase wraps in a transaction fails at deploy time. This rule
reports a transaction-forbidden statement (`CREATE [UNIQUE] INDEX CONCURRENTLY`,
`DROP INDEX CONCURRENTLY`, `REINDEX ... CONCURRENTLY`, `ALTER TABLE ... DETACH
PARTITION ... CONCURRENTLY`, `VACUUM`, `CREATE`/`DROP DATABASE`, `ALTER SYSTEM`,
and, before PostgreSQL 12, `ALTER TYPE ... ADD VALUE`) unless the changeset sets
`runInTransaction="false"`. It checks forward and rollback SQL.

```xml
<!-- Reported: CREATE INDEX CONCURRENTLY cannot run inside a transaction. -->
<changeSet id="010-index" author="me">
    <sql>CREATE INDEX CONCURRENTLY idx ON t (c);</sql>
</changeSet>
```

```xml
<!-- Accepted. -->
<changeSet id="010-index" author="me" runInTransaction="false">
    <sql>CREATE INDEX CONCURRENTLY idx ON t (c);</sql>
</changeSet>
```

#### changeset-single-statement

A `runInTransaction="false"` changeset cannot be rolled back atomically, so a
partial failure with several statements leaves `DATABASECHANGELOG` inconsistent.
This rule reports a `runInTransaction="false"` changeset that contains a
transaction-forbidden statement plus any other statement. Move each statement
into its own changeset. Forward and rollback are evaluated separately.

```xml
<!-- Reported: two statements in one non-transactional changeset. -->
<changeSet id="011" author="me" runInTransaction="false">
    <sql>CREATE INDEX CONCURRENTLY idx ON t (c); DROP INDEX idx2;</sql>
</changeSet>
```

#### changeset-prefer-single-statement

The advisory counterpart of the rule above: a `runInTransaction="false"`
changeset with several statements shares the partial-failure exposure even when
none is transaction-forbidden. It stays quiet when a transaction-forbidden
statement is present (that is the error rule's job), and it fires on forward or
rollback SQL. It is a warning and opt-in because a multi-statement
non-transactional changeset is sometimes intentional.

```yaml
include:
  - changeset-prefer-single-statement
```

#### require-concurrent-index-creation

Adapted from Squawk. A plain `CREATE INDEX` holds a lock that blocks writes for
the whole build; `CONCURRENTLY` avoids it. The rule reports a `CREATE [UNIQUE]
INDEX` whose table is not created in the same changeset (a fresh schema may build
its indexes non-concurrently). It is a token-level heuristic: qualified and
unqualified table references are not reconciled. It is a warning and opt-in.

```xml
<!-- Reported: account already exists, so this blocks writes while it builds. -->
<changeSet id="020" author="me">
    <createIndex indexName="account_email_idx" tableName="account">
        <column name="email"/>
    </createIndex>
</changeSet>
```

#### require-concurrent-index-deletion

Adapted from Squawk. A plain `DROP INDEX` takes an exclusive lock on the index,
blocking queries that use it; `CONCURRENTLY` avoids it. The rule reports a
`DROP INDEX`. It is a warning and opt-in.

#### changeset-rollback-required

A changeset with no rollback cannot be reverted cleanly. The rule reports a
changeset that declares no `<rollback>`. It is accepted when a `<rollback>` block
is present (including an empty one, or `<rollback changeSetId="..."/>` that
references another changeset) and when the changeset is `runOnChange` with a
stored-routine body (re-running the body replaces it). It is a warning and
opt-in.

```xml
<!-- Reported: no rollback. -->
<changeSet id="030" author="me">
    <sqlFile path="sql_changes/030-add-column.sql" relativeToChangelogFile="true"/>
</changeSet>
```

#### changeset-rollback-parity

A shallow heuristic (it cannot prove `DROP TABLE t` inverts `CREATE TABLE t`)
that a rollback should have about as many statements as the forward SQL. It
reports a rollback with statements that covers less than half the forward
statement count — for example a multi-statement changeset whose rollback only
reverses the last change. A reference-only rollback and routine bodies are
ignored, and a missing rollback is `changeset-rollback-required`'s concern. It is
a warning and opt-in.

#### routine-dynamic-sql

A PL/pgSQL body that uses `EXECUTE` assembles SQL at runtime, so the token rules
cannot see the statements it runs. This rule reports such a routine body so the
linter does not imply the changeset is clean; it complements `plpgsql_check`
(which analyses the static body, not the dynamic string). It only fires on
routine bodies, is informational and opt-in.

```xml
<!-- Reported (info): the body's dynamic SQL is invisible to the token rules. -->
<changeSet id="function-app.foo" author="me" runOnChange="true">
    <createProcedure path="functions/app/foo.sql" relativeToChangelogFile="true"/>
</changeSet>
```

#### block-raw-alter-table

A hand-written `ALTER TABLE` takes `ACCESS EXCLUSIVE` (or `SHARE ROW EXCLUSIVE`
for a foreign key) without the bounded `lock_timeout` and the lock settings a
project resolves through its own wrappers. Where a project exposes lock-aware DDL
helpers (for example the `ddl_utils` schema, whose wrappers read
`get_lock_settings`), this rule reports a literal `ALTER TABLE` in changelog SQL
and points the developer at the matching helper, naming the table and the
operation.

Forward and rollback SQL are both checked, because a raw rollback `ALTER TABLE`
takes the same lock. A structured change type (`<addColumn>`,
`<modifyDataType>`, ...) generates SQL elsewhere and is out of scope, and a
stored-routine body is the helper's own implementation rather than a caller's raw
statement, so both are exempt. The rule is an error and on by default. A project
whose changelog genuinely needs a raw `ALTER TABLE` accepts it with a whitelist
entry.

```xml
<!-- Reported: call ddl_utils.add_column instead. -->
<changeSet id="040" author="me">
    <sql>ALTER TABLE ddl_utils.example ADD COLUMN note text;</sql>
</changeSet>
```

```xml
<!-- Accepted: the lock-aware wrapper. -->
<changeSet id="041" author="me">
    <sql>SELECT ddl_utils.add_column('ddl_utils', 'example', 'note', 'text');</sql>
</changeSet>
```

#### require-dbms-postgresql

PostgreSQL-only SQL in a changeset that does not declare `dbms="postgresql"` is
a portability trap: a changelog reused on another target fails at deploy rather
than at review. This rule reports several constructs with no portable
equivalent — `CONCURRENTLY` (index and partition operations), `CREATE DOMAIN`,
`CREATE EXTENSION`, `LANGUAGE plpgsql`, `USING gin`/`gist`/`brin`/`spgist`, and
the casts and operators common in `<sql>` bodies (`::`, `->`, `->>`, `@>`, ...).

The gate can be on the changeset or, as Liquibase applies it too, on an
individual SQL source (`dbms` is case-insensitive and may be a comma-separated
list). Structured change types are exempt, and a mention in a string or comment
does not count. It is a token-level heuristic — an unlisted PostgreSQL-only
construct is a false negative — and an error on by default.

```xml
<!-- Reported: CREATE INDEX CONCURRENTLY is PostgreSQL-only. -->
<changeSet id="050-index" author="me">
    <sql>CREATE INDEX CONCURRENTLY idx ON t (c);</sql>
</changeSet>
```

```xml
<!-- Accepted. -->
<changeSet id="050-index" author="me" dbms="postgresql">
    <sql>CREATE INDEX CONCURRENTLY idx ON t (c);</sql>
</changeSet>
```

### Configuration

The configuration is a per-module `.liquibase-linter.yml`, passed with
`--config` (the default is the working directory's file, so a Maven module pins
its own path):

```yaml
pgVersion: '17'
failOn: error
exclude:
  - changeset-prefer-single-statement
include:
  - require-concurrent-index-creation
  - routine-dynamic-sql
rules:
  changeset-single-statement:
    severity: warning
```

| Key         | Meaning                                                                                                              |
|-------------|----------------------------------------------------------------------------------------------------------------------|
| `pgVersion` | PostgreSQL major version for version-gated rules (`ALTER TYPE ... ADD VALUE`); omit when unknown                     |
| `failOn`    | least severity that fails the run: `error` (default), `warning`, `info` or `none` (the CLI `--fail-on` overrides it) |
| `exclude`   | rule ids to turn off                                                                                                 |
| `include`   | opt-in rule ids to turn on                                                                                           |
| `rules`     | per-rule `severity` override and free-form `options`                                                                 |

Unknown keys, an unknown rule id, and a malformed value fail fast, so a typo
cannot silently disable a rule.

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

### Tokenizer

The rules match a lossless token stream, not a parse tree: every byte of the
input maps to a token, so a finding can point at an exact line and column. The
lexer understands the PostgreSQL lexical forms the changelog files use:

- whitespace; `--` line comments; nested `/* ... */` block comments;
- standard string literals (`'...'` with doubled quotes) and the `B'...'`,
  `X'...'` and `E'...'` (backslash-escape) forms;
- dollar-quoted strings (`$$...$$` and `$tag$...$tag$`), which is how routine
  bodies appear — a whole body is one token;
- quoted identifiers (`"..."` with doubled double quotes);
- numeric literals (including the `0x`, `0o`, `0b`, `L` and `_` forms) and
  positional parameters (`$1`);
- operators and punctuation, and bare words (keywords are matched by rules with
  `Token.matchesKeyword`, so identifier/keyword classification is not baked in);
- Liquibase formatted-SQL directives (`--liquibase`, `--changeset`,
  `--rollback`, `--preconditions`, `--property`, `--comment`) as trivia.

Input the lexer cannot classify — for example an unterminated string — becomes an
`ERROR` token rather than being skipped, so a rule (or a future rule) can report
it. Unicode-escape strings and identifiers (`U&'...'` / `U&"..."`) are not
supported and become an `ERROR` token. There is no parser and no
identifier/type resolution; a rule that needs those is out of scope for this
tokenizer (see [docs/adr/0001-sql-lexer-approach.md](docs/adr/0001-sql-lexer-approach.md)).

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
the job needs `security-events: write`. A pull request from a fork gets a
read-only token, so the upload step is skipped there to avoid a red check;
same-repo pull requests and manual runs still publish.

## Using the library

The library is published to GitHub Packages from a `v*` tag (for example
`v1.1.0`), and each artifact version is immutable:

```xml

<dependency>
    <groupId>io.github.eyupmiduck</groupId>
    <artifactId>liquibase-validation</artifactId>
    <version>1.1.0</version>
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
3. Push a matching tag, e.g. `git tag v1.1.0 && git push origin v1.1.0`.

The `Publish` workflow fails if the tag does not equal `v` + the POM version.
Consumer POMs must then be updated to the new version explicitly.

## Building

The `PlpgsqlCheck` and `AuditColumnsCheck` tests start PostgreSQL containers (the
former builds a small image with the `plpgsql_check` package), so Docker must be
running:

```sh
./mvnw verify
```
