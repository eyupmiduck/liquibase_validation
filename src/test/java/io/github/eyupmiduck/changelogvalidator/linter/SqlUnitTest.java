package io.github.eyupmiduck.changelogvalidator.linter;

import io.github.eyupmiduck.changelogvalidator.linter.lexer.SqlLexer;
import io.github.eyupmiduck.changelogvalidator.linter.model.SqlSource;
import io.github.eyupmiduck.changelogvalidator.linter.sql.SqlStatementSplitter;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@link SqlUnit#commands()}: an unsplit source is re-split for
 * command-level analysis, while a source Liquibase splits keeps its statements.
 */
class SqlUnitTest {

    private static SqlUnit unit(boolean splitStatements, String delimiter, String sql) {
        SqlSource source = new SqlSource(SqlSource.Kind.INLINE_SQL, null, sql, splitStatements, delimiter, true, null);
        return new SqlUnit(source, Path.of("/db/changes.sql"), sql, SqlLexer.tokenize(sql),
                SqlStatementSplitter.split(sql, splitStatements, delimiter, true));
    }

    /**
     * An unsplit source remains one Liquibase statement but exposes both commands
     * to the rules.
     */
    @Test
    void resplitsAnUnsplitSource() {
        SqlUnit unit = unit(false, ";", "SELECT 1; SELECT 2;");

        assertEquals(1, unit.statements().size());
        assertEquals(2, unit.commands().size());
    }

    /**
     * An unsplit source with a custom non-operator delimiter such as GO is
     * re-split on that delimiter rather than on a semicolon.
     */
    @Test
    void honoursTheEndDelimiterWhenResplitting() {
        SqlUnit unit = unit(false, "GO", "SELECT 1 GO SELECT 2 go");

        assertEquals(2, unit.commands().size());
    }

    /**
     * When Liquibase already splits the source, the analysis commands are the
     * same statements.
     */
    @Test
    void keepsTheLiquibaseStatementsWhenSplittingIsOn() {
        SqlUnit unit = unit(true, ";", "SELECT 1; SELECT 2;");

        assertEquals(unit.statements(), unit.commands());
    }
}
