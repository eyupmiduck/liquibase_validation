package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.Rule;
import io.github.eyupmiduck.changelogvalidator.linter.RuleContext;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import io.github.eyupmiduck.changelogvalidator.linter.SqlUnit;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.Token;
import io.github.eyupmiduck.changelogvalidator.linter.sql.SqlStatement;
import io.github.eyupmiduck.changelogvalidator.linter.sql.TransactionForbiddenClassifier;
import io.github.eyupmiduck.changelogvalidator.linter.sql.TransactionForbiddenClassifier.Classification;
import io.github.eyupmiduck.changelogvalidator.linter.sql.TransactionForbiddenClassifier.Family;

import java.util.ArrayList;
import java.util.List;

/**
 * Requires a non-transactional changeset that contains a statement PostgreSQL
 * forbids inside a transaction block to contain that statement and nothing else.
 *
 * <p>{@code runInTransaction="false"} means Liquibase cannot roll a partial
 * failure back, so a changeset with several statements can leave
 * {@code DATABASECHANGELOG} in an invalid state. Forward and rollback SQL are
 * evaluated separately.
 *
 * <p>This rule assumes the run-in-transaction rule (bead ddl-w8y.11) has already
 * required {@code runInTransaction="false"}; it only fires on changesets that
 * already set it.
 */
public final class SingleStatementRule implements Rule {

    /** The rule id. */
    public static final String ID = "changeset-single-statement";

    private final Integer pgVersion;

    /**
     * Creates the rule with an unknown PostgreSQL version, so version-gated
     * statements are not classified.
     */
    public SingleStatementRule() {
        this(null);
    }

    /**
     * Creates the rule.
     *
     * @param pgVersion the PostgreSQL major version for version-gated statements,
     *                  or null when unknown
     */
    public SingleStatementRule(Integer pgVersion) {
        this.pgVersion = pgVersion;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.ERROR;
    }

    @Override
    public List<Violation> check(RuleContext context) {
        if (context.changeSet().runInTransaction()) {
            return List.of();
        }
        List<Violation> violations = new ArrayList<>();
        collect(context.forward(), violations);
        collect(context.rollback(), violations);
        return List.copyOf(violations);
    }

    private void collect(List<SqlUnit> units, List<Violation> violations) {
        int statementCount = units.stream().mapToInt(unit -> unit.statements().size()).sum();
        if (statementCount <= 1) {
            return;
        }
        for (SqlUnit unit : units) {
            List<Classification> classifications = TransactionForbiddenClassifier.classify(
                    unit.tokens(), unit.statements(), pgVersion);
            if (!classifications.isEmpty()) {
                Classification classification = classifications.get(0);
                Token token = firstToken(unit, classification.statement());
                violations.add(new Violation(
                        display(classification.family())
                                + " must be the only statement in a runInTransaction=\"false\" changeset (found "
                                + statementCount + " statements)",
                        "Move each statement into its own changeset with runInTransaction=\"false\".",
                        unit.file(), token.line(), token.column()));
                return;
            }
        }
    }

    private static Token firstToken(SqlUnit unit, SqlStatement statement) {
        for (Token token : unit.tokens()) {
            if (token.startOffset() >= statement.startOffset()) {
                return token;
            }
        }
        throw new IllegalStateException("no token for statement at offset " + statement.startOffset());
    }

    private static String display(Family family) {
        return switch (family) {
            case CREATE_INDEX_CONCURRENTLY -> "CREATE INDEX CONCURRENTLY";
            case DROP_INDEX_CONCURRENTLY -> "DROP INDEX CONCURRENTLY";
            case REINDEX_CONCURRENTLY -> "REINDEX CONCURRENTLY";
            case DETACH_PARTITION_CONCURRENTLY -> "ALTER TABLE ... DETACH PARTITION CONCURRENTLY";
            case CREATE_DATABASE -> "CREATE DATABASE";
            case DROP_DATABASE -> "DROP DATABASE";
            case ALTER_SYSTEM -> "ALTER SYSTEM";
            case VACUUM -> "VACUUM";
            case ALTER_TYPE_ADD_VALUE -> "ALTER TYPE ... ADD VALUE";
        };
    }
}
