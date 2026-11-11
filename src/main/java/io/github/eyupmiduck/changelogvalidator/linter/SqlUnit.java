package io.github.eyupmiduck.changelogvalidator.linter;

import io.github.eyupmiduck.changelogvalidator.linter.lexer.Token;
import io.github.eyupmiduck.changelogvalidator.linter.model.SqlSource;
import io.github.eyupmiduck.changelogvalidator.linter.sql.SqlStatement;
import io.github.eyupmiduck.changelogvalidator.linter.sql.SqlStatementSplitter;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * One SQL source of a changeset, read and lexed so rules can inspect it.
 *
 * @param source     the model source
 * @param file       the file the SQL came from: a SQL file, or the changelog
 *                   file for inline SQL
 * @param sql        the SQL text
 * @param tokens     the lexed tokens
 * @param statements the statements Liquibase would execute
 * @param commands   the commands to analyse, computed once when the unit is
 *                   created
 */
public record SqlUnit(SqlSource source, Path file, String sql, List<Token> tokens, List<SqlStatement> statements,
                      List<SqlStatement> commands) {

    /**
     * Validates the unit's required components and copies its lists. When
     * {@code commands} is null (the convenience constructor), it is derived from
     * {@code statements} or by re-splitting an unsplit source.
     */
    public SqlUnit {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(tokens, "tokens");
        Objects.requireNonNull(statements, "statements");
        if (commands == null) {
            commands = source.splitStatements()
                    ? statements
                    : SqlStatementSplitter.split(sql, true, source.endDelimiter(), source.stripComments());
        }
        tokens = List.copyOf(tokens);
        statements = List.copyOf(statements);
        commands = List.copyOf(commands);
    }

    /**
     * Creates a unit and derives its command list.
     *
     * @param source     the model source
     * @param file       the file the SQL came from
     * @param sql        the SQL text
     * @param tokens     the lexed tokens
     * @param statements the statements Liquibase would execute
     */
    public SqlUnit(SqlSource source, Path file, String sql, List<Token> tokens, List<SqlStatement> statements) {
        this(source, file, sql, tokens, statements, null);
    }
}
