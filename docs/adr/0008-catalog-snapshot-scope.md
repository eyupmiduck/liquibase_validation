# ADR 0008: Keep CatalogSnapshot a narrow test-support helper

- **Status:** Accepted
- **Date:** 2026-11-23
- **Bead:** lv-ahk.21 (epic lv-ahk)

## Context

The previous-release upgrade test was re-baselined rather than retired, so
`CatalogSnapshot` still has a job: it captures the PostgreSQL catalog of a set
of schemas and compares a from-scratch build with an upgraded database, and it
does the same for the round-trip and re-apply contracts.

That leaves a scope question. `CatalogSnapshot` could remain the
catalog-equivalence engine for those two contracts, or it could grow into a
general schema-fingerprint utility. A general utility would need broader catalog
coverage and would be used outside the upgrade and round-trip tests.

## Decision

Keep `CatalogSnapshot` a **narrow test-support helper** for:

- `UpgradeTestBase`, which compares the previous release upgraded to the current
  changelog with a database built from the current changelog alone;
- `ChangelogRoundTripContract`, which compares the baseline, the rolled-back
  state and the re-applied state.

It is not a general schema-fingerprint utility. The supported object set stays
as it is, and the gaps are documented in the class Javadoc, the README and this
ADR. When a consumer needs a gap covered, add a focused query to
`CatalogSnapshot` with a test, rather than turning the class into a general
tool.

No `CatalogSnapshot` code is dead. `capture`, `dump`, `compare`,
`assertEquivalent`, `DEFAULT_DUMP_DIRECTORY`, `Report` and `Change` are used by
the two contracts or their tests, so nothing is removed for this decision.

## Supported objects

Schemas; relations (tables, partitioned tables, views, materialized views and
foreign tables); columns; constraints; indexes; sequences; domains;
composite/enum/range types; functions and procedures (per signature); triggers;
extensions; ACLs and comments. Extension-owned objects, the `liquibase`
tracking schema and the system schemas are excluded, as are row data, OIDs,
sequence `last_value` and statistics.

## Known gaps

A difference in any of these is **not** detected by the catalog comparison:

- aggregates and window functions;
- row-level security policies;
- rules;
- sequence ownership (`OWNED BY`);
- column storage, compression and statistics;
- inheritance;
- tablespaces;
- user-defined base and multirange types.

## Consequences

Positive:

- The comparison stays focused on the objects the two contracts are meant to
  protect, so it is fast and stable.
- No new comparison surface means no new false positives from objects that
  legitimately differ (for example a tablespace chosen by the environment).
- The limits are explicit, so a consumer knows when a schema change is outside
  the upgrade test's reach.

Negative:

- A regression in one of the gaps can pass the upgrade and round-trip tests.
  The changelog linter and the catalog contracts (comments, privileges, routine
  signatures) cover some of those objects, but not all.

## Revisit triggers

- A consumer starts using one of the gaps in a way the upgrade test must catch.
- The round-trip contract needs a broader fingerprint than the upgrade test.
- A new PostgreSQL object kind becomes part of the project's schema convention.

## References

- Bead: `lv-ahk.21`.
- Previous-release upgrade test: ADR 0005.
- Round-trip and re-apply contracts: README, "Round trip and re-apply".
