# ADR 0006: Project-level changelog analysis

- **Status:** Accepted
- **Date:** 2026-11-23
- **Bead:** lv-ahk.18 (epic lv-ahk)

## Context

The linter's rules are mostly per changeset: a rule receives one changeset and
its loaded SQL. Some problems only exist across the graph:

- the same table, index, column or constraint declared twice;
- a foreign key whose referenced table is never created;
- a `NOT VALID` constraint that is never validated;
- a changeset that uses a table created by a later changeset;
- an object commented twice, with the same or conflicting text;
- a routine file that no changeset references.

Detecting them needs the file graph, the effective properties, the changeset
order, and the loaded SQL for every changeset. It also needs to be opt-in: a
project can have objects installed outside the changelog, and the rules are
heuristic.

## Decision

Add a `ChangelogProject` model and a `ProjectAnalysisRule` interface.

- `ChangelogModel.project(root, master)` returns the project: the visited files
  with their includes and effective properties, the flat changeset list in
  execution order, and the effective global properties. `ChangelogModel.changesets`
  delegates to it.
- `ProjectAnalysisRule` receives a `ProjectContext` containing the project and a
  list of `AnalyzedChangeSet` values (the changeset plus its loaded
  `RuleContext`). It can report a finding at a changeset or at a file with no
  changeset.
- `Linter` gains a constructor and `lint(ChangelogProject)` overload that run
  per-changeset rules, changeset-only project rules, then project-analysis
  rules. The existing `lint(List<ChangeSet>)` builds a minimal project, so a
  caller with only changesets keeps working; file-level analysis rules do
  nothing for that project.
- The built-in project-analysis rules are opt-in warnings:
  `project-duplicate-object`, `project-foreign-key-target`,
  `project-unvalidated-constraint`, `project-ordering`,
  `project-conflicting-comment` and `project-unregistered-routine`.
  `changeset-id-unique` remains the duplicate-changeset-id rule.
- `project-foreign-key-target` and `project-ordering` accept a `knownTables`
  option for tables installed outside the project.
- `Rules.projectAnalysisRules(config)` registers the rules; the CLI and
  `ChangelogLinterContract` pass them to the linter.

## Consequences

Positive:

- A project can opt into cross-changeset checks without changing its schema.
- The rules reuse the existing token stream, so they see literal SQL and the
  structured change types the model renders.
- The model is available to a project that wants its own analysis.

Limits / deferred:

- The rules are token-level and conservative. `project-duplicate-object` does
  not model a rename; `project-ordering` checks table-level use, not columns or
  `SELECT`; `project-unvalidated-constraint` skips unnamed constraints; and
  `project-conflicting-comment` only examines forward SQL.
- `project-unregistered-routine` overlaps with `ChangelogSqlFilesContract`.
- No consumer adoption is required. The rules are opt-in warnings, so enabling
  them is a separate follow-up.

## References

- Epic: bead `lv-ahk`; this decision: `lv-ahk.18`.
- Structured rendering: `lv-ahk.17` and ADR 0003.
