# ADR 0001: Use a hand-written PostgreSQL lexer for the changelog linter

- **Status:** Accepted
- **Date:** 2026-09-21
- **Bead:** ddl-w8y.1 (linter epic ddl-w8y, tracked in `ddl_utils`)

## Context

The planned changelog linter tokenizes SQL found in Liquibase changelogs and
applies rules that combine the token stream with Liquibase changeset semantics (`runInTransaction`, `dbms`,
`splitStatements`/`endDelimiter`, contexts,
rollback). The first rules are phrase-level: `CREATE INDEX CONCURRENTLY`,
`DROP INDEX CONCURRENTLY` and `REINDEX ... CONCURRENTLY` must live in a
`runInTransaction="false"` changeset and be that changeset's only statement.

The linter must also understand Liquibase formatted-SQL directives (`--liquibase formatted sql`, `--changeset`,
`--rollback`, `--preconditions`,
`--property`, `--comment`) and reproduce Liquibase's statement splitting. It does
not need to resolve identifiers, types or expressions in its first iteration.

`liquibase-validation` is pure Java 25 with no runtime dependency beyond
snakeyaml, published to GitHub Packages as immutable releases, and run both in
CI and — eventually — as a CLI by downstream projects.

## Decision

Implement a **hand-written PostgreSQL tokenizer in Java**, and defer parsing.

- The lexer covers nested block comments, line comments, standard strings (`''` doubling), `E'...'` strings (backslash
  escapes), dollar-quoted strings (`$$`, `$tag$...$tag$`), quoted identifiers (`""` doubling), operators and
  punctuation, numeric literals, and bare keywords/identifiers.
- It is lossless: every byte maps to a token, with byte and line/column offsets,
  so diagnostics are precise and the input can be reconstructed.
- Whitespace and comments are trivia tokens and are excluded from statement
  counts. Liquibase formatted-SQL directives are recognised as a thin layer over
  comment tokens and emitted as directive tokens.
- Statement splitting is a separate component (bead ddl-w8y.5) that consumes the
  token stream and reproduces Liquibase's `splitStatements`/`endDelimiter`
  behaviour.
- Rules match token sequences plus changeset context. There is no AST in v1.
- The existing `ddl_utils_lib.has_top_level_comma` PL/pgSQL scanner (comments,
  strings, dollar quoting, quoted identifiers, `E'...'`) is the reference
  implementation; port that state machine.

## Alternatives considered

- **libpg_query (pganalyze) via JNI or a subprocess.** The real PostgreSQL
  scanner/parser, and the most accurate option, but it has no first-class Java
  binding (Ruby/Go/Python/Rust/JS). Shipping or requiring native binaries per
  OS/architecture is disproportionate for a small, largely test-scope library
  and complicates GitHub Packages releases and downstream CI. Liquibase
  directives and statement splitting would still need a separate layer. Revisit
  if a rule needs an AST.
- **jOOQ's parser.** Pure Java and already used by `ddl_utils`, but it is a
  jOOQ-DSL parser rather than a general PostgreSQL parser: DDL and PL/pgSQL
  coverage is partial, and using it only to tokenize would add a large
  dependency to a library that currently has none. Revisit if we want a jOOQ AST
  and accept the dependency.
- **JSqlParser / Apache Calcite.** Pure Java, but partial PostgreSQL coverage (`CONCURRENTLY`, dollar quoting,
  PL/pgSQL), extra dependencies, and no
  awareness of Liquibase directives. Rejected.

## Consequences

Positive:

- No new runtime or native dependencies; portable across the CI matrix and for
  downstream consumers.
- Fast, deterministic, and unit-testable without Docker or a database.
- A single tokenizer serves both generic SQL rules and Liquibase-specific needs (directives, statement splitting).
- Straightforward for contributors to extend.

Negative / risks:

- We own lexer correctness (e.g. `U&'...'` Unicode escapes, operator edge cases,
  nested comments). Mitigate with a comprehensive table-driven test suite and a
  documented supported subset.
- No semantic analysis: rules that need to resolve columns or types will require
  a future parser or libpg_query.
- Possible drift from PostgreSQL's own lexer over time; keep a version note and
  a reference corpus of tricky inputs.

## Revisit triggers

- A rule needs identifier resolution, type/expression validation, or another
  AST-level capability.
- Lexer correctness or maintenance cost starts to exceed the cost of embedding
  libpg_query.
- Liquibase introduces statement-splitting behaviour we cannot reproduce at the
  token level.

## References

- Linter epic: bead `ddl-w8y`; this decision: `ddl-w8y.1`.
- Reference scanner: `ddl_utils_lib.has_top_level_comma`.
- PostgreSQL lexical structure documentation.
