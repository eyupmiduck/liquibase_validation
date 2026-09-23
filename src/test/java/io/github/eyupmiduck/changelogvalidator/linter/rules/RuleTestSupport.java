package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.RuleContext;
import io.github.eyupmiduck.changelogvalidator.linter.SqlUnit;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.SqlLexer;
import io.github.eyupmiduck.changelogvalidator.linter.model.ChangeSet;
import io.github.eyupmiduck.changelogvalidator.linter.model.SqlSource;
import io.github.eyupmiduck.changelogvalidator.linter.sql.SqlStatementSplitter;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * The fixtures shared by the rule tests: an inline/routine/file SQL unit, a
 * changeset builder and a couple of context builders, so the tests do not each
 * re-create the same setup.
 */
final class RuleTestSupport {

    /**
     * The changelog file the fixtures report against.
     */
    static final Path FILE = Path.of("/db/changes.sql");

    private RuleTestSupport() {
    }

    /**
     * An inline-SQL unit for {@code sql}.
     *
     * @param sql the SQL
     * @return the unit
     */
    static SqlUnit unit(String sql) {
        return unit(SqlSource.Kind.INLINE_SQL, sql);
    }

    /**
     * A unit of the given kind for {@code sql}; a file kind uses {@link #FILE} as
     * its path.
     *
     * @param kind the source kind
     * @param sql  the SQL
     * @return the unit
     */
    static SqlUnit unit(SqlSource.Kind kind, String sql) {
        SqlSource source = switch (kind) {
            case ROUTINE_BODY -> new SqlSource(kind, FILE, null, true, ";", false, null);
            case SQL_FILE -> new SqlSource(kind, FILE, null, true, ";", true, null);
            case INLINE_SQL -> new SqlSource(kind, null, sql, true, ";", true, null);
        };
        return new SqlUnit(source, FILE, sql, SqlLexer.tokenize(sql), SqlStatementSplitter.split(sql));
    }

    /**
     * A changeset with the given attributes and no SQL sources.
     *
     * @param runInTransaction whether Liquibase wraps the changeset in a transaction
     * @param runOnChange      whether Liquibase re-runs the changeset when it changes
     * @param dbms             the changeset dbms restriction, or null
     * @param rollbackDefined  whether the changeset declares a rollback
     * @return the changeset
     */
    static ChangeSet changeSet(boolean runInTransaction, boolean runOnChange, String dbms, boolean rollbackDefined) {
        return new ChangeSet("cs-1", "me", FILE, runInTransaction, runOnChange, dbms, null, null,
                List.of(), rollbackDefined, List.of());
    }

    /**
     * A changeset carrying one inline source and no rollback.
     *
     * @param sql              the inline SQL
     * @param runInTransaction whether Liquibase wraps the changeset in a transaction
     * @return the changeset
     */
    static ChangeSet inlineChangeSet(String sql, boolean runInTransaction) {
        return new ChangeSet("cs-1", "me", FILE, runInTransaction, false, null, null, null,
                List.of(new SqlSource(SqlSource.Kind.INLINE_SQL, null, sql, true, ";", true, null)),
                false, List.of());
    }

    /**
     * A forward-only context for an inline {@code sql}, with a default
     * transactional changeset.
     *
     * @param sql the inline SQL
     * @return the context
     */
    static RuleContext forwardOnly(String sql) {
        return context(changeSet(true, false, null, false), unit(sql));
    }

    /**
     * A context with the given changeset and forward units (no rollback units).
     *
     * @param changeSet the changeset
     * @param forward   the forward units
     * @return the context
     */
    static RuleContext context(ChangeSet changeSet, SqlUnit... forward) {
        return new RuleContext(changeSet, List.of(forward), List.of());
    }

    /**
     * A context with a changeset whose transaction flag is set, the forward SQL and
     * optional rollback SQL.
     *
     * @param runInTransaction whether Liquibase wraps the changeset in a transaction
     * @param forwardSql       the forward SQL, or null
     * @param rollbackSql      the rollback SQL units
     * @return the context
     */
    static RuleContext context(boolean runInTransaction, String forwardSql, String... rollbackSql) {
        List<SqlUnit> forward = forwardSql == null ? List.of() : List.of(unit(forwardSql));
        List<SqlUnit> rollback = Arrays.stream(rollbackSql).map(RuleTestSupport::unit).toList();
        return new RuleContext(changeSet(runInTransaction, false, null, false), forward, rollback);
    }

    /**
     * A context with a transactional changeset, the forward SQL and optional
     * rollback SQL.
     *
     * @param forwardSql  the forward SQL, or null
     * @param rollbackSql the rollback SQL units
     * @return the context
     */
    static RuleContext context(String forwardSql, String... rollbackSql) {
        List<SqlUnit> forward = forwardSql == null ? List.of() : List.of(unit(forwardSql));
        List<SqlUnit> rollback = Arrays.stream(rollbackSql).map(RuleTestSupport::unit).toList();
        return new RuleContext(changeSet(true, false, null, false), forward, rollback);
    }
}
