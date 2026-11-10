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
 */
public record SqlUnit(SqlSource source, Path file, String sql, List<Token> tokens, List<SqlStatement> statements) {

    /**
     * Validates the unit's required components and copies its lists.
     */
    public SqlUnit {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(tokens, "tokens");
        Objects.requireNonNull(statements, "statements");
        tokens = List.copyOf(tokens);
        statements = List.copyOf(statements);
    }

    /**
     * Returns the commands to inspect when Liquibase executes the source in one
     * go. When {@code splitStatements} is true this is {@link #statements()};
     * otherwise the source is re-split on its end delimiter (or {@code ;}) so
     * rules can still see multiple commands. A delimiter the tokenizer cannot
     * represent, such as {@code $$}, remains a single command and is documented
     * as unsupported.
     *
     * @return the statements to analyse, in source order
     */
    public List<SqlStatement> commands() {
        if (source.splitStatements()) {
            return statements;
        }
        String delimiter = source.endDelimiter().isBlank() ? ";" : source.endDelimiter();
        return SqlStatementSplitter.split(sql, true, delimiter, source.stripComments());
    }
}
