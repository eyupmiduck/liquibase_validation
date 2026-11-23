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
  exempt from the `NNN-` SQL naming rule. The separate
  `ChangelogLayoutCheck` joins a changeset id to its forward and rollback files
  and, for a routine or trigger, to the declaration inside the file.
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
- `io.github.eyupmiduck.changelogvalidator.CatalogCommentsCheck` — checks the
  catalog-comment convention on a set of schemas: every schema, table, column,
  domain, composite/enum/range/base type and function/procedure (and, by
  default, trigger) has a non-blank comment. An explicit allow-list accepts a
  missing comment, and an entry that accepts nothing is stale.
- `io.github.eyupmiduck.changelogvalidator.linter` — a changelog linter: a
  hand-written PostgreSQL tokenizer, Liquibase-compatible statement splitting, a
  changeset model, a rule engine with tty/JSON/SARIF reporters, and rules that
  combine SQL tokens with Liquibase changeset semantics. See the
  [rule reference](#rule-reference), [configuration](#configuration) and
  [tokenizer](#tokenizer) sections.
- `io.github.eyupmiduck.changelogvalidator.testing` — shared test support,
  published under the `tests` classifier: a Testcontainers `PostgresTestBase`
  (shared container, migrated template database, per-class clone, introspection
  and SQLSTATE helpers, and a `ProjectProfile` carrying the
  database/changelog `Configuration` plus the managed and routine schemas,
  caller role and rollback directories), a classpath `ChangelogTestSupport`,
  `TestResources`, the `ChangelogAssertions`/`RoutineAssertions` helpers,
  contract classes for the linter, changelog naming, orphaned SQL, tracking
  tables and `plpgsql_check`,
  and an `UpgradeTestBase` that
  applies the most recent published release's changelog and then the current one
  (see [Using the library](#using-the-library),
  [docs/adr/0004-shared-test-support-packaging.md](docs/adr/0004-shared-test-support-packaging.md)
  and
  [docs/adr/0005-previous-release-upgrade-test.md](docs/adr/0005-previous-release-upgrade-test.md)).

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
Statement splitting supports the default `;` and a single-token, non-operator
delimiter such as `GO`; operator and dollar-quote delimiters such as `/` and
`$$` are not supported. When a source sets `splitStatements="false"`, the
rules still analyse its commands separately so a forbidden statement cannot hide
behind another command.

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
| [`routine-select-star`](#routine-select-star)                                     | error    | on      | `SELECT *`, `alias.*` or `table.*` in a routine body                                                       |
| [`routine-transaction-control`](#routine-transaction-control)                     | error    | on      | `COMMIT` or `ROLLBACK` in a function body                                                                  |
| [`routine-argument-naming`](#routine-argument-naming)                             | error    | on      | a routine parameter or local without its `i_`/`o_`/`io_`/`l_` prefix                                       |
| [`block-raw-alter-table`](#block-raw-alter-table)                                 | error    | on      | a raw `ALTER TABLE` in changelog SQL                                                                       |
| [`require-dbms-postgresql`](#require-dbms-postgresql)                             | error    | on      | PostgreSQL-only SQL in a changeset without a `dbms="postgresql"` gate                                      |
| [`routine-run-on-change-required`](#routine-run-on-change-required)               | error    | on      | a routine changeset without `runOnChange="true"`                                                           |
| [`trigger-create-or-replace`](#trigger-create-or-replace)                         | error    | on 14+  | a `CREATE TRIGGER` without `OR REPLACE`                                                                    |
| [`require-not-valid-constraint`](#require-not-valid-constraint)                   | warning  | opt-in  | a validating `CHECK`/`FOREIGN KEY` added to an existing table without `NOT VALID`                          |
| [`require-if-exists-on-drop`](#require-if-exists-on-drop)                         | warning  | opt-in  | a supported `DROP` without `IF EXISTS`                                                                     |
| [`changeset-author-required`](#changeset-author-required)                         | error    | on      | a changeset without a non-blank `author`                                                                   |
| [`changeset-file-name-matches-id`](#changeset-file-name-matches-id)               | error    | on      | a numbered changeset whose SQL or rollback path does not match its id                                      |
| [`changeset-id-unique`](#changeset-id-unique)                                     | error    | on      | a duplicate changeset id/author/file identity (whole-graph rule)                                           |

`changeset-id-unique` is a project rule: it runs once over the whole graph after
the per-changeset rules. `trigger-create-or-replace` is on for PostgreSQL 14+;
when `pgVersion` is below 14 it is opt-in and reports nothing.

Every rule is suppressed the same way: an `exclude` entry turns it off for the
module, a `rules` severity override downgrades it (for example to `info`, which
`--fail-on error` ignores), and a whitelist entry accepts one specific finding.
The per-rule sections give an example of each.

#### changeset-run-in-transaction-required

PostgreSQL rejects several statements inside a transaction block; running one in
a changeset Liquibase wraps in a transaction fails at deploy time. This rule
reports a transaction-forbidden statement (`CREATE [UNIQUE] INDEX CONCURRENTLY`,
`DROP INDEX CONCURRENTLY`, `REINDEX ... CONCURRENTLY`, `REINDEX
SCHEMA`/`DATABASE`/`SYSTEM`, `ALTER TABLE ... DETACH PARTITION ... CONCURRENTLY`,
`VACUUM`, `CREATE`/`DROP DATABASE`, `CREATE`/`DROP TABLESPACE`, `ALTER SYSTEM`,
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

A changeset with no explicit rollback cannot be reverted cleanly. The rule
requires an explicit `<rollback>`, including for structured change types that
Liquibase can invert automatically, so an empty `<rollback/>` accepts a change
that needs no reversal. It is accepted when a `<rollback>` block is present (including an empty one, or
`<rollback changeSetId="..."/>` that references
another changeset) and when the changeset is `runOnChange` with a stored-routine
body (re-running the body replaces it). It is a warning and opt-in.

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

The rule extracts and re-lexes the dollar-quoted body, so an `EXECUTE` in a
comment or a string literal is not reported. A known dynamic-SQL boundary can be
allow-listed by routine name:

```yaml
rules:
  routine-dynamic-sql:
    options:
      allowedRoutines:
        - ddl_utils_lib.alter_table
```

```xml
<!-- Reported (info): the body's dynamic SQL is invisible to the token rules. -->
<changeSet id="function-app.foo" author="me" runOnChange="true">
    <createProcedure path="functions/app/foo.sql" relativeToChangelogFile="true"/>
</changeSet>
```

#### routine-select-star

`SELECT *` (and `alias.*` or `table.*`) ties a routine's result and `INTO`
assignment to the current column order, so a later table change silently changes
what the routine returns. The rule reports a star in a body's select list,
including a nested subquery, in forward and rollback SQL. It is an error and on
by default.

A star inside a function call is not a select-list star: `count(*)` is accepted,
as is a star in a string or a comment.

```sql
-- Reported: the result changes when app.orders gains a column.
SELECT *
FROM app.orders;

-- Accepted.
SELECT o.id, o.total
FROM app.orders o;
```

#### routine-transaction-control

A PostgreSQL function runs inside the caller's transaction and cannot manage it;
`COMMIT` or `ROLLBACK` in a function body fails at run time. The rule reports
either statement in a function body, in forward and rollback SQL. A procedure may
manage transactions, so procedure bodies are left alone. It is an error and on by
default.

```sql
-- Reported in a function.
CREATE FUNCTION app.f() RETURNS void AS
$$
BEGIN
    COMMIT;
END;
$$ LANGUAGE plpgsql;
```

#### routine-argument-naming

A prefix makes a routine body readable without the declaration in view: an input
parameter starts with `i_`, an output parameter with `o_`, an input/output
parameter with `io_`, and a local variable with `l_`. The rule checks the
declaration's named parameters and the locals a PL/pgSQL body declares in a
`DECLARE` block or as a `FOR` loop target. It is an error and on by default.

Unnamed parameters (`integer`, `double precision`) and SQL-language bodies have
no names to check. A name in a comment or a string is ignored, and
`SELECT ... FOR UPDATE` is not mistaken for a loop declaration.

```sql
-- Reported: the parameter and the local lack their prefixes.
CREATE FUNCTION app.f(x integer) RETURNS void AS
$$
DECLARE
    total integer;
BEGIN
    total := x;
END;
$$ LANGUAGE plpgsql;

-- Accepted.
CREATE FUNCTION app.f(i_x integer) RETURNS void AS
$$
DECLARE
    l_total integer;
BEGIN
    l_total := i_x;
END;
$$ LANGUAGE plpgsql;
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
individual SQL source (`dbms` is case-insensitive). The gate must restrict
execution to PostgreSQL: a comma-separated list that also names another database
does not satisfy it, because the SQL would still run on that target. Structured
change types are exempt, and a mention in a string or comment does not count. It
is a token-level heuristic — an unlisted PostgreSQL-only construct is a false
negative — and an error on by default.

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

#### routine-run-on-change-required

A stored-routine body is a living definition, so Liquibase must re-apply the
changeset when the file changes. Without `runOnChange`, the database silently
keeps the old body. The rule reports a changeset with a PostgreSQL routine source (`createProcedure`/`createFunction`)
that is not `runOnChange="true"`. It is an
error and on by default.

```xml
<!-- Reported. -->
<changeSet id="function-app.foo" author="me">
    <createProcedure path="functions/app/foo.sql" relativeToChangelogFile="true"/>
</changeSet>
```

```xml
<!-- Accepted. -->
<changeSet id="function-app.foo" author="me" runOnChange="true">
    <createProcedure path="functions/app/foo.sql" relativeToChangelogFile="true"/>
</changeSet>
```

#### trigger-create-or-replace

A plain `CREATE TRIGGER` cannot be re-applied after a body change. The rule
reports it and points at `CREATE OR REPLACE TRIGGER` (PostgreSQL 14+), in forward
and rollback SQL. It is an error and on by default on PostgreSQL 14+; below 14 it
is opt-in and reports nothing. The constraint-trigger form
`CREATE [OR REPLACE] CONSTRAINT TRIGGER` is recognised too.

```xml
<!-- Reported: the body is CREATE TRIGGER ... -->
<changeSet id="trigger-app.foo" author="me">
    <sqlFile path="triggers/app/foo.sql" relativeToChangelogFile="true"/>
</changeSet>
```

#### require-not-valid-constraint

Adding a validating `CHECK` or `FOREIGN KEY` to an existing table scans the table
while holding a blocking lock. Add it `NOT VALID` and validate it separately;
a table created in the same changeset has no existing rows and is exempt. The
rule reports the validating form on an existing table. It is a warning and
opt-in, and it checks forward and rollback SQL.

```sql
-- Reported.
ALTER TABLE app.orders
    ADD CONSTRAINT orders_total_check CHECK (total >= 0);

-- Accepted.
ALTER TABLE app.orders
    ADD CONSTRAINT orders_total_check CHECK (total >= 0) NOT VALID;
```

Only `CHECK` and `FOREIGN KEY` are checked: PostgreSQL cannot add `UNIQUE`,
`PRIMARY KEY` or `EXCLUDE` as `NOT VALID`; those need a `USING INDEX` strategy.

#### require-if-exists-on-drop

A `DROP` without `IF EXISTS` fails when a re-applied or partially rolled-back
changeset finds the object already gone. The rule reports `DROP TABLE`, `DROP
FUNCTION`, `DROP PROCEDURE`, `DROP TRIGGER` and `DROP INDEX`, including
`DROP INDEX CONCURRENTLY`, in forward and rollback SQL. It is a warning and
opt-in.

```sql
-- Reported.
DROP TABLE app.orders;

-- Accepted.
DROP TABLE IF EXISTS app.orders;
```

#### changeset-author-required

The author is part of Liquibase's changeset identity and the history a project
uses to find who made a change. The rule reports a missing, blank or
whitespace-only `author`. It is an error and on by default.

```xml
<!-- Reported. -->
<changeSet id="001-create" author="">
    <sqlFile path="sql_changes/001-create.sql" relativeToChangelogFile="true"/>
</changeSet>
```

#### changeset-file-name-matches-id

A numbered changeset's forward file must be `sql_changes/<id>.sql` and its
rollback `rollback/<id>-rollback.sql`. The rule reports a filename or directory
that does not match the id. It is an error and on by default. Routine and trigger
ids have their own path convention and are left to `ChangelogLayoutContract`.

```xml
<!-- Reported: the changeset is 001-create but the file is 002-create.sql. -->
<changeSet id="001-create" author="me">
    <sqlFile path="sql_changes/002-create.sql" relativeToChangelogFile="true"/>
</changeSet>
```

#### changeset-id-unique

Liquibase identifies a changeset by id, author and file, so a repeat is
ambiguous. This whole-graph project rule reports every occurrence after the first
with the same id, author and normalized changelog file. It is an error and on by
default.

```xml
<!-- Reported: the second changeset repeats the first identity. -->
<changeSet id="001-create" author="me">...</changeSet>
<changeSet id="001-create" author="me">...</changeSet>
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

The only option today is `routine-dynamic-sql`'s `allowedRoutines` (a list of
`schema.name` routines that may build SQL dynamically). Unknown keys, an unknown
rule id, and a malformed value fail fast, so a typo cannot silently disable a
rule.

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
- numeric literals (including the `0x`, `0o`, `0b` and underscore-separator
  forms) and positional parameters (`$1`); the `L` suffix is not a PostgreSQL
  numeric form and is not recognised;
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

The same artifact also publishes a `tests` classifier with shared test support
(`io.github.eyupmiduck.changelogvalidator.testing`): `ChangelogTestSupport`,
`PostgresTestBase` (with a `ProjectProfile` value carrying the database and
changelog `Configuration`, the managed/routine schemas, the caller role and the
rollback directories, so a project's regular test base and every contract share
one setup, plus the generic lock and catalog helpers), `TestResources`, the
`ChangelogAssertions`/`RoutineAssertions` helpers (including routine/rollback
parity), the previous-release `UpgradeTestBase`, and abstract contract classes (`ChangelogLinterContract`,
`ChangelogNamingContract`, `ChangelogSqlFilesContract`, `ChangelogLayoutContract`,
`TrackingTablesContract`, `PlpgsqlCheckContract`, `RollbackSignatureContract`,
`CommentContract`, `AuditColumnsContract`, `RoutineSignatureContract`,
`RoutineDocumentationContract`, `PrivilegeContract`, `RoutineSecurityContract`,
`ChangelogRoundTripContract`, `ChangelogReapplyContract`, `UpgradeContract`)
that a project subclasses to inherit the tests themselves, so a project that
builds a Liquibase changelog and PostgreSQL routines can share one implementation
instead of copying it (see
[docs/adr/0004-shared-test-support-packaging.md](docs/adr/0004-shared-test-support-packaging.md)):

```xml

<dependency>
    <groupId>io.github.eyupmiduck</groupId>
    <artifactId>liquibase-validation</artifactId>
    <version>1.1.0</version>
    <type>test-jar</type>
    <scope>test</scope>
</dependency>
```

Test-jar dependencies are not transitive, so the consumer declares its own
JUnit, Testcontainers (`testcontainers-postgresql`), jOOQ, `liquibase-core` and
PostgreSQL driver at test scope.

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

### Changelog layout

`ChangelogLayoutContract` checks the in-place file layout that a changeset id
promises:

- `NNN-name` uses `sql_changes/NNN-name.sql` and
  `rollback/NNN-name-rollback.sql`;
- `function-<schema>.<name>`, `procedure-<schema>.<name>` and
  `trigger-<schema>.<name>` use the matching `functions/`, `procedures/` or
  `triggers/` path and its `-rollback` directory;
- a routine or trigger file declares exactly the object its path names, and a
  trigger is on a table in the path's schema;
- a file-based changeset has its matching rollback file unless the project
  overrides `rollbackRequired()`, and no SQL file is referenced twice.

The contract returns its `ProjectProfile`, so the changelog locations come from
the same values as the other contracts. The rule complements
`ChangelogNamingContract`: naming checks the shape of an id and a file name,
while layout checks that they agree with each other and with the SQL declaration.

### Catalog comments

`CatalogCommentsCheck` enforces the project-wide comment convention. It inspects
the configured schemas and reports every missing or blank comment on:

- a schema;
- a base, partitioned or foreign table (not a partition, view or materialized
  view);
- a column of those tables;
- a domain, composite, enum, range or user-defined base type, excluding array
  types and a relation's implicit row type;
- a function or procedure, including a trigger function, excluding
  extension-owned routines and PostgreSQL's support routines for a type;
- a trigger, when `Options.includeTriggers()` is true (the default).

`CommentContract` reads the profile's managed schemas and defaults to requiring
trigger comments. Override `allowedObjects()` for an explicit exception; an entry
that accepts no missing comment is stale and fails. The contract also fails when
no object was inspected, so a misspelled schema cannot pass.

### Audit columns

`AuditColumnsContract` checks that every base and partitioned table (not a
partition) in the profile's managed schemas has `created_at` and `updated_at` as
`timestamptz NOT NULL DEFAULT now()` and an enabled unrestricted
`BEFORE UPDATE ... FOR EACH ROW` trigger. The contract fails when the schema set
contains no table, so a misconfigured schema cannot pass.

Override `probeTables()` with a schema-to-table map to run the behavioral probe
on a real row: it verifies the trigger refreshes `updated_at` and preserves
`created_at`, then rolls the update back. A probe table with no row fails. Leave
the map empty to run only the catalog check. Consumers seed the probe row in
their own fixture; the contract does not invent data.

### Routine signatures and documentation

`RoutineSignatureContract` checks every function and procedure in the profile's
routine schemas: a routine name must have one signature, and an intentional
overload must be listed in `allowedOverloads()`. An allow-list entry that no
longer matches a real overload is stale and fails, so a removed exception cannot
hide a new overload. The contract fails non-vacuously when the routine schemas
contain no routine.

`RoutineDocumentationContract` checks that every routine has a README section
and that no README section names a routine that no longer exists. The default
format is a Markdown heading:

```markdown
### `schema.name(i_arg, ...)`
```

The default parser strips optional-argument brackets and compares the argument
names and arity to the catalog. A heading containing `...` is treated as an
abbreviated signature and is checked by name only. Override
`documentedRoutines(resource, content)` for a different format and
`readmeResources()` for different files. The contract defaults to the functions
and procedures READMEs under the profile's changelog root and is non-vacuous.

The contracts are separate because a project may adopt the signature check
before its routine READMEs are complete.

### Privileges

`PrivilegeContract` checks the caller grants and the default-privilege hardening:

- no routine in the profile's routine schemas is executable by `PUBLIC`
- the caller role can execute every callable routine (procedures and
  non-trigger functions)
- every configured `applicationTypes()` entry is not usable by `PUBLIC`, and
  the caller has an explicit `USAGE` grant on it
- a routine and an enum type created after the default-privilege changeset do
  not regain `PUBLIC` access (skipped when `defaultPrivilegesExpected()` is
  false)
- the grant changeset rollback mirrors the forward grants and has no stale
  revoke or stale `PUBLIC` restore

`applicationTypes()` is the configured type/domain list and must be non-empty.
The routine checks fail when the routine schemas contain no routine. The default
grant resources are
`<changes>/sql_changes/999-grant-privileges.sql` and
`<changes>/rollback/999-grant-privileges-rollback.sql`; override them for a
different name.

### Routine security

`RoutineSecurityContract` checks the routine security conventions:

- only routines listed in `allowedSecurityDefiners()` may be
  `SECURITY DEFINER`; an allow-list entry that is no longer a definer is stale
- a `SECURITY DEFINER` routine must pin a `search_path` that includes
  `pg_catalog`
- each routine in `catalogRoutines()` must pin a `search_path` that includes
  `pg_catalog`
- no routine may pin a caller-controlled or mutable path: `$user`, `DEFAULT`
  and `public` are rejected
- each routine in `expectedProcedures()` must really be a procedure

The contract is non-vacuous. `routineSchemas()` defaults to the profile's
routine schemas; consumers supply the definer allow-list, the catalog-routine
list and the expected procedures.

### Round trip and re-apply

Two live contracts prove that a changelog is a living definition rather than a
write-once script. They share the project's one container and create only a
scratch database per contract class.

`ChangelogRoundTripContract` applies the configured changelog to a fresh
database, rolls every changeset back in reverse order, asserts the managed
objects are gone or restored to the baseline, and applies the changelog again.
It compares a `CatalogSnapshot` of `roundTripSchemas()` before the changelog,
after rollback and after re-apply, so a rollback that leaves an object behind
fails. The default rollback assertion also checks each managed schema is present
exactly when it was in the baseline.

`ChangelogReapplyContract` applies the changelog and its fixtures twice; the
second run must apply no changeset and must not error. It proves that
`runOnChange`, preconditions and repeatable definitions are safe to re-run.

Both contracts read the changelog and fixtures from the project profile. Two
profile fields cover the awkward cases:

- `baselineChangelogs` are applied **before** the main changelog and are
  deliberately left outside its rollback, for a dependency another project
  installs (for example `ddl_utils` alongside `dml_utils`). A baseline schema is
  still captured by `roundTripSchemas()` and must survive the rollback.
- `roundTripSchemas` defaults to `managedSchemas`, but may add a fixture schema
  the fixture changelog creates, so the rollback of the fixtures is covered too.

```java
static final ProjectProfile PROFILE = ProjectProfile.builder(CONFIGURATION)
        .managedSchemas("my_project", "my_project_lib")
        .roundTripSchemas("my_project", "my_project_lib", "my_project_fixtures")
        .baselineChangelogs("db/dependency/db.changelog-master.xml")
        .build();
```

A project with a deliberate `runAlways` changeset overrides
`repeatableChangeSetIds()` with the ids the second run may apply. An installed
prerequisite that is not a changelog can use `prepareBaseline(Connection)`.

These are Testcontainers tests and belong in `verify`, not in the per-change
unit-test loop: a round trip applies and rolls back the whole graph, so it is
seconds to minutes, not milliseconds. The Maven Failsafe plugin is the natural
home when a project separates unit and integration tests; the existing
Testcontainers suite in this library runs them under Surefire in `verify`.

### Incremental upgrade from the previous release

`UpgradeTestBase` checks the path a real consumer takes: it applies the **most
recent published release** of the project's own artifact, then the current
changelog, to a scratch database in the shared container. Liquibase records the
same `FILENAME` for a changeset applied from the released jar and from the
current classpath, so the second run applies only the delta; a changeset that
was released and then edited fails Liquibase's checksum validation.

Extend the shared `UpgradeContract`, supply the coordinates of the project's own
artifact and the project-specific assertions, and return the project profile from
your test base:

```java
abstract class PostgresTestBase extends io.github.eyupmiduck.changelogvalidator.testing.PostgresTestBase {

    static final Configuration CONFIGURATION = new Configuration(
            "my_project", "my_project_owner", "my_project_owner",
            "my_project_test", "my_project_test",
            "my-project-postgres:17-alpine",
            "db/changelog/db.changelog-master.xml",
            "my_project_databasechangelog", "my_project_databasechangeloglock",
            null,
            // Installs the image's extensions and grants into each database
            // before the changelog runs; null when the project needs none.
            PostgresTestBase::installProjectExtensions);

    static final ProjectProfile PROFILE = ProjectProfile.builder(CONFIGURATION)
            .managedSchemas("my_project", "my_project_lib")
            .routineSchemas("my_project", "my_project_lib")
            .callerRole("my_project_caller")
            .routineRollbackDirectories("db/changelog/changes/functions-rollback")
            .triggerRollbackDirectories("db/changelog/changes/triggers-rollback")
            .roundTripSchemas("my_project", "my_project_lib")
            .build();

    @Override
    protected ProjectProfile projectProfile() {
        return PROFILE;
    }
}

class BaselineUpgradeTest extends UpgradeContract {

    @Override
    protected PublishedRelease.Coordinates releaseCoordinates() {
        return new PublishedRelease.Coordinates(
                "https://maven.pkg.github.com/eyupmiduck/my_project",
                "io.github.eyupmiduck", "my_project", "1.2.0-SNAPSHOT");
    }

    @Override
    protected void assertUpgradedDatabase() {
        assertEquals(2, upgradeDsl().fetchOne(
                        "SELECT count(*) FROM pg_namespace WHERE nspname LIKE 'my_project%'")
                .get(0, Integer.class));
    }
}
```

The fixture-free version resolves the release automatically: it reads
`maven-metadata.xml` from the repository, selects the highest non-`SNAPSHOT`
version strictly below the current version, and downloads the jar to
`target/previous-release` (override `cacheDirectory()` to change that). The
roles, image, tracking-table names, changelog resource and managed schemas come
from the profile; a project declares one `ProjectProfile` constant next to its
test base, so its regular tests and every contract read the same values.

Credentials are read from `GITHUB_ACTOR`/`GITHUB_TOKEN` (or `GH_TOKEN`), falling
back to the `github` server in `~/.m2/settings.xml` with `${env.VAR}`
placeholders expanded. CI must grant `packages: read` (the workflow that already
fetches `liquibase-validation` does) and the repository must have read access to
the package.

Beyond applying cleanly, the base also builds a database from the current
changelog **alone** and asserts the two schemas match, object by object (`CatalogSnapshot`, direct `pg_catalog`
queries). It catches an upgrade that runs
but leaves a schema different from a fresh install: a missing or extra column, a
constraint or index only the fresh path gets, a stale routine overload, a
forgotten comment, a grant only one path has. Overloads are compared per
signature. Both captures are written one file per object under
`target/catalog-snapshot/{fresh,upgrade}`, so a difference can be reviewed with
`diff -r` and uploaded as a build artifact. The comparison covers
`managedSchemas()` and can be turned off with `catalogComparisonEnabled()`.

The capture covers schemas, relations (tables, partitioned tables, views,
materialized views and foreign tables), columns, constraints, indexes,
sequences, domains, composite/enum/range types, functions and procedures,
triggers, extensions, ACLs and comments. It deliberately does not cover
aggregates and window functions, row-level security policies, rules, sequence
ownership (`OWNED BY`), column storage/compression/statistics, inheritance,
tablespaces, or user-defined base and multirange types; a difference in one of
those is not detected by the catalog comparison.

When there is no published release below the current version it skips (so a
project's first release does not break its build), and it can be disabled for an
offline run with `-Dskip.upgrade-test=true`. See
[docs/adr/0005-previous-release-upgrade-test.md](docs/adr/0005-previous-release-upgrade-test.md).

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
