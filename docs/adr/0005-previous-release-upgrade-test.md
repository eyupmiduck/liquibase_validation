# ADR 0005: Test the incremental upgrade from the most recent published release

- **Status:** Accepted
- **Date:** 2026-10-17
- **Bead:** lv-9op.1 (epic lv-9op, tracked in `liquibase_validation`)

## Context

`dml_utils` and `ddl_utils` ship their schema as a Liquibase changelog inside
the published jar. Every test that touches the changelog starts from an **empty
database**: the jOOQ codegen container and
`testing.PostgresTestBase` both apply the full changelog to a fresh database.
Nothing ever exercises the path a real consumer takes — applying a new release
**on top of** an already-deployed older release.

That gap hides a class of upgrade bugs:

- a changeset that was released and then edited in place (Liquibase checksum
  drift: a real deploy fails validation);
- an `ALTER`/`DROP`/index changeset against an object a fresh build happens to
  create differently;
- a new changeset that assumes an object a fresh build gets from `CREATE`, but
  that the upgrade path never creates;
- a signature change that leaves a stale routine overload behind, because a
  fresh build never had the old one.

The requirement is an integration test that takes the **most recent published
release** of a project's own artifact, applies its changelog, then applies the
current changelog, and fails when the upgrade does not apply cleanly. Because
the changelog is identical in shape across the two projects, the logic belongs
in `liquibase-validation`, alongside the rest of the shared test support.

The hard question is how Liquibase treats the same changeset applied from a
released jar and then from the current build. Liquibase keys a changeset by
`id` + `author` + **`FILENAME`**. If the two runs resolve different `FILENAME`
values for a shared changeset, the second run tries to re-apply it and fails on
objects that already exist. A throwaway spike (2026-10-17, `dml_utils` 1.5.1)
established that this does not happen when both runs address the changelog at
the same resource path:

- loading the release jar with `new URLClassLoader(new URL[]{jar}, null)`
  (`parent = null`, so the test classpath's current changelog cannot shadow it)
  and a `ClassLoaderResourceAccessor`, then loading the current changelog from
  the test classpath, records the **same** `db/changelog/changes/*.xml`
  `FILENAME` in both runs;
- the spike applied **53** changesets from the jar and then exactly **1** new
  changeset, with no checksum or identity error.

The spike also confirmed the input is obtainable: GitHub Packages serves
`maven-metadata.xml` with a `<latest>` release (`dml_utils` 1.5.1, `ddl_utils`
1.1.0, `liquibase-validation` 1.4.0), and the release jar downloads (the
registry answers `302` and redirects to blob storage).

## Decision

Add a shared, **deploy-only** upgrade test to the `testing` package, published
under the `tests` classifier per [ADR 0004](0004-shared-test-support-packaging.md).

1. **Placement.** The support is test support (it needs Testcontainers, jOOQ,
   `liquibase-core` and the PostgreSQL driver), so it lives in
   `src/test/java` under `io.github.eyupmiduck.changelogvalidator.testing` and
   ships in the `tests` classifier. It is parameterized by the consumer's
   coordinates, roles, schemas and image; project-specific wiring stays in each
   consumer.

2. **Loading the previous release.** Apply the release jar's changelog with a
   `URLClassLoader` over the jar and `parent = null`, wrapped in a
   `ClassLoaderResourceAccessor`; apply the current changelog with the normal
   classpath accessor. `parent = null` is load-bearing: the current changelog is
   on the test classpath at the same resource path, so any parent loader would
   win and silently test nothing. Both runs address
   `db/changelog/db.changelog-master.xml`, which keeps `FILENAME` identical and
   makes the second run apply only the delta.

3. **Discovery.** Resolve the previous release automatically from GitHub
   Packages `maven-metadata.xml`: the highest non-SNAPSHOT version **strictly
   below** the current version. This keeps the test current without a
   hand-maintained property.

4. **Credentials.** Prefer the `GITHUB_ACTOR`/`GITHUB_TOKEN` environment
   variables; otherwise read `~/.m2/settings.xml` server `github`, resolving
   `${env.VAR}` placeholders. `actions/setup-java` writes the server entry with
   a `${env.GITHUB_TOKEN}` password, so a naive settings reader would pass the
   literal placeholder through; both paths must work in CI and locally.

5. **Assertion.** Deploy-only: the previous release then the current changelog
   applies without error. A modified released changeset makes the second
   `update()` fail checksum validation, which is the intended failure mode and
   is covered by a test.

6. **No previous release.** Skip with a JUnit assumption rather than fail, so a
   project's first release does not break its own build. Provide an opt-out
   system property for offline runs.

7. **Container.** Reuse the single shared PostgreSQL container; the upgrade test
   creates its own scratch database rather than cloning the migrated template,
   because a template already at the current schema has nothing to upgrade, and
   a class-order-dependent template would make the test vacuous.

## Alternatives considered

- **Vendor the released changelog into the test resources.** No network or
  credentials, but the copy drifts from the registry and stops representing
  "the most recent published release". Rejected.
- **A Maven version-range dependency** (`[1.0.0,${revision})`) so Maven resolves
  the previous release. No custom resolver code, but range resolution is
  fragile, depends on registry metadata caching, and still needs the version
  passed to the test. Rejected in favour of the explicit metadata lookup.
- **A hand-maintained `<previous.release.version>` property.** Simple and
  offline, but it forgets to move and then tests a stale release. Rejected.
- **Assert the upgraded catalog equals a from-scratch build.** Much stronger,
  but larger (a full catalog diff) and beyond the first iteration. Deferred to
  a follow-up bead; the deploy-only test is the floor.

## Consequences

Positive:

- An upgrade bug fails CI in the project that introduced it, not a consumer's
  deploy.
- One implementation shared by both consumers; each supplies only coordinates,
  roles, schemas and image.
- No new production dependency: the resolver uses the JDK `HttpClient` and a
  minimal metadata parse (Maven's `ComparableVersion` may be added at test
  scope for ordering).

Negative / risks:

- The test needs network access and a `packages: read` token (both consumers
  already grant it); it must be skippable for offline builds.
- The `URLClassLoader` must stay parent-free; a regression to the default parent
  would make the test pass while testing nothing. A test asserts the delta, so
  a silent no-op is caught.
- The deploy-only assertion cannot prove the upgraded schema equals a
  from-scratch build; it proves the migration path executes.
- The support is released on the library's cadence, so the consumers pick it up
  a release later.

## Revisit triggers

- A real upgrade bug is found that clears the deploy-only assertion but where
  the resulting schema differs; then implement the from-scratch comparison.
- A third consumer appears whose artifact layout or registry differs enough that
  the parameterization no longer fits.
- GitHub Packages stops serving `maven-metadata.xml`, or the redirect no longer
  works from `HttpClient` without extra handling.

## References

- Epic: bead `lv-9op`; this decision: `lv-9op.1`.
- Precedent: [ADR 0004](0004-shared-test-support-packaging.md) (shared test
  support under the `tests` classifier).
