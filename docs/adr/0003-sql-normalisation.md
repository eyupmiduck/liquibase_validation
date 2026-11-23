# ADR 0003: Normalise changelog SQL before lexing

- **Status:** Accepted
- **Date:** 2026-09-22
- **Bead:** ddl-w8y.6 (linter epic ddl-w8y, tracked in `ddl_utils`)

## Context

The linter's rules match SQL tokens, but the SQL in a changelog is not always
the SQL Liquibase runs. Three transformations stand between them:

- **`${property}` placeholders** are substituted from the changelog
  `<property>` elements (and, at runtime, from command-line/system properties).
- **`<modifySql>`** children of a changeset (`append`, `prepend`, `replace`,
  `regExpReplace`, `appendSqlIfNotPresent`) rewrite the SQL before execution,
  and can be filtered by `dbms`, `context`, `labels` and `applyToRollback`.
- **Structured change types** (`<createIndex>`, `<createTable>`, ...) generate
  SQL that never appears literally in the changelog, so a token rule cannot see
  it.

Rules that miss these see the wrong SQL, or no SQL at all. In particular, the
`createIndex` change type cannot express `CONCURRENTLY`, so
`require-concurrent-index-creation` (bead ddl-w8y.14) could not check it.

## Decision

Normalise each SQL source **inside the linter, before lexing**, using a
`Normalisation` carried by `ChangeSet` (`ChangelogModel` reads it):

1. **Property substitution.** Collect `<property name value>` elements across
   the reachable changelog files. Liquibase's rule is "the first set value
   wins", so the first value a name receives is kept. Replace `${name}` (a
   value may reference another property) up to a small depth; an undefined
   placeholder is left as-is, because the linter cannot see properties supplied
   on the Liquibase command line.
2. **`<modifySql>`.** Parse a changeset's `<modifySql>` children and apply them
   in declaration order, mirroring Liquibase's `SqlVisitor`s exactly (`append`/`prepend` concatenate, `replace` is
   literal, `regExpReplace` uses a
   `java.util.regex.Pattern`, `appendSqlIfNotPresent` appends unless the SQL
   already ends with the value). Rollback SQL only receives visitors declared
   `applyToRollback="true"`; a visitor whose `dbms` does not include PostgreSQL
   is skipped.
3. **Structured change types.** Render the change types a rule needs into a
   synthetic inline `SqlSource`. The supported set is `createTable`,
   `createIndex`, `dropIndex`, `addColumn`, `dropColumn`, `renameColumn`,
   `modifyDataType`, `addNotNullConstraint`, `dropNotNullConstraint`,
   `addDefaultValue`, `dropDefaultValue`, `addForeignKeyConstraint`,
   `addUniqueConstraint`, `addCheckConstraint`, `addPrimaryKey`,
   `dropPrimaryKey`, `createSequence` and `dropSequence`. A structured type
   that is not rendered, or one missing a required attribute, contributes no
   source rather than invalid SQL.
4. **Rules stay polymorphic over the SQL.** No rule knows about properties,
   modifySql or change types; it sees the normalised SQL and its statement list.

The transformations are applied to inline, file and routine-body sources alike,
so property substitution and modifySql work wherever the SQL lives.

## Alternatives considered

- **Normalise in `ChangelogModel` and hand rules pre-read text.** The model
  would have to read `sqlFile`/routine files and replace their path with text,
  losing the file a finding points at and the lazy reading the model relies on.
  Rejected.
- **Normalise in each rule.** Duplicates the property/modifySql logic in every
  rule and makes the changelog-level inputs part of each rule's contract.
  Rejected.
- **Depend on Liquibase to generate the SQL.** The library has no Liquibase
  dependency and the CLI must stay dependency-light (ADR 0002). Rejected.
- **A full SQL parser for structured change types.** Out of scope; a best-effort
  DDL rendering is enough for token rules.

## Consequences

Positive:

- Rules see the SQL Liquibase runs, so property- and modifySql-driven SQL is
  linted, and the structured index change types reach
  `require-concurrent-index-creation`/`-deletion`.

Limits / deferred:

- **Change-type coverage is partial.** The renderers above cover the production
  DDL change types, but other structured types (`createView`, `loadData`,
  `addLookupTable`, `mergeColumns`, ...) still contribute no source and are
  invisible to token rules. Extend `ChangelogModel.structuredSql` as rules need
  them.
- **Structured SQL is approximate.** A renderer emits the PostgreSQL shape the
  token rules need, not an exact reproduction of Liquibase's generator. For
  example it does not carry every column constraint, tablespace or storage
  option. Property placeholders in rendered attribute values are still
  substituted by the normaliser because the synthetic source goes through the
  same SQL pipeline.
- **Context/label filtering is not applied.** The linter has no run context, so
  it evaluates every changeset and every `modifySql` whose `dbms` is not
  PostgreSQL. A `dbms`-filtered visitor is honoured; a `context`/`labels`-filtered
  one is applied unconditionally. Changeset-level `dbms`/`context`/`labels`
  filtering likewise needs a run context and is a follow-up.
- **Property files and command-line properties are not read.** Only changelog
  `<property name value>` definitions are used; `file`-based and
  `-D`/`--changelog-parameters` values are left unresolved.
- **Rendered SQL is approximate.** It is shaped to match the token rules, not to
  be executed; a rule that needs exact DDL would need real generation.

## Update (1.14.0): formatted SQL sources

A formatted SQL changelog's `--changeset` body is an inline `SqlSource` with the
changeset's own `splitStatements`, `endDelimiter` and `stripComments` (and the
`rollback*` variants for the rollback source). Property substitution applies to
it like any inline source; formatted SQL has no `<modifySql>` equivalent, so the
`modifySql` list is empty. An unformatted SQL file found by `<includeAll>` is a
`SQL_FILE` source and is read lazily like an XML `sqlFile`; a formatted SQL file
found the same way is parsed into its changesets.

## References

- Linter epic: bead `ddl-w8y`; this decision: `ddl-w8y.6`.
- Motivating rule: `ddl-w8y.14` (`require-concurrent-index-creation`).
- ADR 0002: linter packaging and consumer integration.
