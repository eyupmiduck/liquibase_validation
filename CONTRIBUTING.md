# Contributing to liquibase-validation

Thanks for your interest in liquibase-validation. This document covers how to
build, test, and release changes.

## Prerequisites

- **JDK 25** — the build targets Java 25.
- **Maven wrapper** — always use `./mvnw`; do not rely on a globally installed
  Maven.
- **Docker** — the `PlpgsqlCheck` and `AuditColumnsCheck` tests start PostgreSQL
  containers through Testcontainers, so Docker must be running for
  `./mvnw verify`.

The only runtime dependency is `snakeyaml` (for the YAML config and allow-lists);
everything else is test-scoped (JUnit, Testcontainers, the PostgreSQL driver,
Jackson and the SARIF schema validator).

## Build and test

```sh
./mvnw verify                                       # full build and tests (needs Docker)
./mvnw test -Dtest=ChangelogValidatorTest           # one test class
```

The build fails on any javac lint warning (`-Xlint:all -Werror`), enforces
`requireMavenVersion`, `requireJavaVersion`, and dependency convergence, and
checks JaCoCo line/branch coverage.

## Project layout

```
src/main/java/io/github/eyupmiduck/changelogvalidator/
    ChangelogValidator.java   changelog graph traversal, naming and orphan checks
    PlpgsqlCheck.java         plpgsql_check static analysis and allow-list
    AuditColumnsCheck.java    audit-column catalog check and behavioral probe
    linter/                   changelog linter (lexer, model, rules, config, reporters, CLI)
```

The public surface is `ChangelogValidator`, `PlpgsqlCheck`, `AuditColumnsCheck`
and the `linter` package. Keep it small and well-documented.

## Java conventions

- Prefer simple, explicit Java over unnecessary abstractions.
- Use modern Java 25 features where they improve readability.
- Public types and methods need Javadoc; keep methods small and focused.
- Do not add comments that restate the code.
- Avoid adding dependencies without a clear benefit.

## Tests

- Use JUnit 5 (`@Test`, `@TempDir`, `assertAll`, `@ParameterizedTest`).
- Every test class and test method needs Javadoc describing the behavior it
  verifies.
- Test observable behavior, not internals. Tests must be deterministic and
  independent.
- Add a regression test when fixing a bug.

## Versioning and releasing

Artifacts are immutable once published to GitHub Packages. To release:

1. Bump `<version>` in `pom.xml` (no `-SNAPSHOT` suffix).
2. Merge to `main`.
3. Push a matching tag: `git tag v1.1.0 && git push origin v1.1.0`.

The `Publish` workflow fails if the tag is not `v` + the POM version. Do not
bump the version in an unrelated change; consumers pin exact versions and are
updated explicitly.

## Branches and commits

- Never commit directly to `main`; work on a feature branch.
- Keep commits focused, with imperative, descriptive messages.
- Do not commit generated build output (`target/`), secrets, credentials, or
  local IDE files.

## Pull requests

Open a PR against `main` and fill in the template. Before requesting review:

1. Review your diff and remove dead code and unused imports.
2. Check for accidental public API changes.
3. Run `./mvnw verify`.
4. Confirm the CI checks are green — the build, CodeQL, and the secret scan run
   on every PR.

## Reporting bugs and security issues

- Bugs and feature requests: use the
  [issue forms](https://github.com/eyupmiduck/liquibase_validation/issues/new/choose).
- Security vulnerabilities: **do not** open a public issue. Follow
  [`SECURITY.md`](SECURITY.md).

## License

By contributing, you agree that your contributions are licensed under the
terms of the project's [LICENSE](LICENSE).
