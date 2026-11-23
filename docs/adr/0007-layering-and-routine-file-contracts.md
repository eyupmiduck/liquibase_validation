# ADR 0007: Schema layering and routine-file contracts

- **Status:** Accepted
- **Date:** 2026-11-23
- **Bead:** lv-ahk.19 (epic lv-ahk)

## Context

Two conventions are easy to state and hard to keep by hand:

- a layered schema set (for example `dml_utils` -> `dml_utils_lib` ->
  `dml_utils_data`) must not let a lower schema reference a higher one;
- every routine or trigger file must declare the object its path names, and its
  rollback file must drop that object.

`ChangelogLayoutContract` already joins a changeset id to its forward and
rollback files, but it trusts the changelog graph. A file that no changeset
references, or a rollback file with no forward file, is invisible to it. A
directory-oriented check closes that gap without duplicating the changeset-id
logic.

A layering violation is not visible in the changelog files alone. It needs the
routine bodies and signatures that PostgreSQL stores, and it must not mistake a
schema name in a comment, a string, or dynamic SQL for a real reference. It also
needs an explicit exception mechanism, because a deliberate upward reference is
sometimes the least-bad option, and a non-vacuity guard, because a misspelled
schema would otherwise inspect nothing and pass.

## Decision

Add two checks and their test-support contracts.

- `SchemaLayeringCheck.inspect(connection, schemas, allowed)` reads
  `pg_proc` for the configured schemas, ordered highest to lowest. It tokenizes
  each routine's `prosrc` body and its arguments (including default expressions)
  and result with the shared `SqlLexer` and looks for `schema.object` references.
  Comments and string literals are not references; a dollar-quoted dynamic SQL
  body is opaque. Extension-owned routines are excluded.
- `SchemaLayeringCheck.Reference` is the allow-list entry. A `null`
  `toObject` matches any object. An entry that matches no reference is stale and
  reported, so a removed exception cannot hide a new reference.
- `SchemaLayeringCheck.Report` also counts the routines and objects (routines, relations and types) per schema.
  `SchemaLayeringContract` fails
  when a configured schema contains none of them, so a misspelled schema cannot
  pass.
- `SchemaLayeringContract` extends `PostgresTestBase`. `schemaLayers()` defaults
  to the profile's managed schemas; a project whose managed schemas are not in
  dependency order overrides it.
- `RoutineFileCheck.findViolations(changesRoot)` walks the `functions/`,
  `procedures/` and `triggers/` trees and their `-rollback` siblings. Each
  forward file must declare exactly one object of the directory's kind, named by
  its path (a trigger by its name and on-table schema). Each rollback file must
  drop that object; several `DROP` statements are accepted when they are all
  overloads of the same routine. A dollar-quoted `DO` block is expanded for the
  drop scan, because a trigger rollback often guards the table first.
- `RoutineFileContract` reads the changes directory from the
  `ProjectProfile` and calls `ChangelogAssertions.assertRoutineFilesFollowConvention`.

## Consequences

Positive:

- A project can enforce layering and file/declaration agreement without copying
  SQL analysis.
- The file check is independent of the changeset graph, so it also catches an
  orphaned file.
- The allow-list and non-vacuity rules make both contracts fail closed.

Limits / deferred:

- The layering check sees only statically written `schema.object` references.
  An unqualified reference resolved through a caller's `search_path`, a name
  built at run time, or a `SET search_path` on the routine is not seen. Projects
  that need those cases pin `search_path` and use the routine-security contract.
- The layering check inspects routines, not table or view definitions. A
  dependency between relations is left to a catalog snapshot or a future check.
- The file check inspects `.sql` files only. A stored object defined inline in a
  changelog changeset is left to the linter and `ChangelogLayoutCheck`.
