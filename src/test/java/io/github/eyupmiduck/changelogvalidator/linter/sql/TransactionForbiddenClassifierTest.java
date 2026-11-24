package io.github.eyupmiduck.changelogvalidator.linter.sql;

import io.github.eyupmiduck.changelogvalidator.linter.lexer.SqlLexer;
import io.github.eyupmiduck.changelogvalidator.linter.sql.TransactionForbiddenClassifier.Classification;
import io.github.eyupmiduck.changelogvalidator.linter.sql.TransactionForbiddenClassifier.Family;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link TransactionForbiddenClassifier}: each transaction-forbidden
 * family is recognised, the {@code ALTER TYPE ... ADD VALUE} version gate is
 * honoured, and statements that are allowed in a transaction (including
 * keywords inside strings) are not classified.
 */
class TransactionForbiddenClassifierTest {

    private static Stream<Arguments> forbiddenStatements() {
        return Stream.of(
                Arguments.of("CREATE INDEX CONCURRENTLY idx ON t (c);", Family.CREATE_INDEX_CONCURRENTLY),
                Arguments.of("CREATE UNIQUE INDEX CONCURRENTLY idx ON t (c);", Family.CREATE_INDEX_CONCURRENTLY),
                Arguments.of("CREATE INDEX CONCURRENTLY IF NOT EXISTS idx ON t (c);",
                        Family.CREATE_INDEX_CONCURRENTLY),
                Arguments.of("DROP INDEX CONCURRENTLY idx;", Family.DROP_INDEX_CONCURRENTLY),
                Arguments.of("REFRESH MATERIALIZED VIEW CONCURRENTLY mv;",
                        Family.REFRESH_MATERIALIZED_VIEW_CONCURRENTLY),
                Arguments.of("REFRESH MATERIALIZED VIEW CONCURRENTLY sch.mv;",
                        Family.REFRESH_MATERIALIZED_VIEW_CONCURRENTLY),
                Arguments.of("REFRESH MATERIALIZED VIEW CONCURRENTLY \"MV\";",
                        Family.REFRESH_MATERIALIZED_VIEW_CONCURRENTLY),
                Arguments.of("REINDEX INDEX CONCURRENTLY idx;", Family.REINDEX_CONCURRENTLY),
                Arguments.of("REINDEX TABLE CONCURRENTLY t;", Family.REINDEX_CONCURRENTLY),
                Arguments.of("REINDEX (CONCURRENTLY) TABLE t;", Family.REINDEX_CONCURRENTLY),
                Arguments.of("REINDEX (VERBOSE, CONCURRENTLY) INDEX i;", Family.REINDEX_CONCURRENTLY),
                Arguments.of("REINDEX DATABASE d;", Family.REINDEX),
                Arguments.of("REINDEX SYSTEM;", Family.REINDEX),
                Arguments.of("REINDEX SCHEMA s;", Family.REINDEX),
                Arguments.of("ALTER TABLE t DETACH PARTITION p CONCURRENTLY;",
                        Family.DETACH_PARTITION_CONCURRENTLY),
                Arguments.of("ALTER TABLE t DETACH PARTITION \"Q1_2024\" CONCURRENTLY;",
                        Family.DETACH_PARTITION_CONCURRENTLY),
                Arguments.of("ALTER TABLE t DETACH PARTITION sch.p CONCURRENTLY;",
                        Family.DETACH_PARTITION_CONCURRENTLY),
                Arguments.of("ALTER TABLE t DETACH PARTITION p1, DETACH PARTITION p2 CONCURRENTLY;",
                        Family.DETACH_PARTITION_CONCURRENTLY),
                Arguments.of("CREATE DATABASE d;", Family.CREATE_DATABASE),
                Arguments.of("DROP DATABASE d;", Family.DROP_DATABASE),
                Arguments.of("CREATE TABLESPACE ts LOCATION '/data/ts';", Family.CREATE_TABLESPACE),
                Arguments.of("DROP TABLESPACE ts;", Family.DROP_TABLESPACE),
                Arguments.of("ALTER SYSTEM SET x = '1';", Family.ALTER_SYSTEM),
                Arguments.of("VACUUM;", Family.VACUUM),
                Arguments.of("VACUUM FULL t;", Family.VACUUM));
    }

    private static Stream<Arguments> allowedStatements() {
        return Stream.of(
                Arguments.of("CREATE INDEX idx ON t (c);"),
                Arguments.of("CREATE INDEX IF NOT EXISTS idx ON t (c);"),
                Arguments.of("DROP INDEX idx;"),
                Arguments.of("REINDEX INDEX idx;"),
                Arguments.of("REINDEX TABLE idx;"),
                Arguments.of("REINDEX TABLE concurrently;"),
                Arguments.of("REINDEX INDEX public.concurrently;"),
                Arguments.of("REFRESH MATERIALIZED VIEW mv;"),
                Arguments.of("REFRESH MATERIALIZED VIEW concurrently;"),
                Arguments.of("CREATE TABLE t (c int);"),
                Arguments.of("ALTER TABLE t ADD COLUMN c int;"),
                Arguments.of("ALTER TABLE t DETACH PARTITION p;"),
                Arguments.of("ALTER TABLE t DETACH PARTITION concurrently;"),
                Arguments.of("ALTER TABLE t DETACH PARTITION \"concurrently\";"),
                Arguments.of("ALTER TABLE t DETACH PARTITION p FINALIZE;"),
                Arguments.of("CREATE SCHEMA x;"),
                Arguments.of("SELECT 1;"),
                Arguments.of("SELECT 'CREATE INDEX CONCURRENTLY';"),
                Arguments.of("SELECT 'DROP DATABASE d';"));
    }

    private static List<Family> families(String sql, Integer pgVersion) {
        return TransactionForbiddenClassifier.classify(
                        SqlLexer.tokenize(sql), SqlStatementSplitter.split(sql), pgVersion)
                .stream()
                .map(Classification::family)
                .toList();
    }

    /**
     * Each forbidden statement is classified as its family.
     *
     * @param sql      the statement
     * @param expected the expected family
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("forbiddenStatements")
    void classifiesForbiddenStatements(String sql, Family expected) {
        assertEquals(List.of(expected), families(sql, 17));
    }

    /**
     * Statements that a transaction permits are not classified.
     *
     * @param sql the statement
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("allowedStatements")
    void ignoresAllowedStatements(String sql) {
        assertTrue(families(sql, 17).isEmpty());
    }

    /**
     * {@code ALTER TYPE ... ADD VALUE} is forbidden before PostgreSQL 12 and
     * allowed from 12, and is not classified when the version is unknown.
     */
    @Test
    void versionGatesAlterTypeAddValue() {
        String sql = "ALTER TYPE mood ADD VALUE 'happy';";

        assertEquals(List.of(Family.ALTER_TYPE_ADD_VALUE), families(sql, 11));
        assertTrue(families(sql, 12).isEmpty());
        assertTrue(families(sql, null).isEmpty());
    }

    /**
     * The grammar forms must be adjacent, so an identifier named
     * {@code concurrently} or an {@code ADD} that is not {@code ADD VALUE} is not
     * misclassified.
     */
    @Test
    void requiresGrammarAdjacency() {
        assertTrue(families("CREATE INDEX idx ON t (concurrently);", 17).isEmpty());
        assertTrue(families("ALTER TYPE mood ADD ATTRIBUTE value int;", 11).isEmpty());
        assertTrue(families("ALTER TYPE comp ADD ATTRIBUTE (add value);", 11).isEmpty());
        assertTrue(families("ALTER TYPE comp ADD ATTRIBUTE (add value, other int);", 11).isEmpty());
    }

    /**
     * {@code familyOf} ignores trivia, so a direct caller may pass raw lexer
     * output and a comment between the partition name and the option does not
     * hide the option.
     */
    @Test
    void familyOfIgnoresTrivia() {
        String sql = "ALTER TABLE t DETACH PARTITION /*c*/ p CONCURRENTLY;";

        assertEquals(java.util.Optional.of(Family.DETACH_PARTITION_CONCURRENTLY),
                TransactionForbiddenClassifier.familyOf(SqlLexer.tokenize(sql), 17));
        // A trailing delimiter is not the required object name after the option.
        assertTrue(TransactionForbiddenClassifier.familyOf(
                SqlLexer.tokenize("REINDEX TABLE concurrently;"), 17).isEmpty());
        assertTrue(TransactionForbiddenClassifier.familyOf(
                SqlLexer.tokenize("REFRESH MATERIALIZED VIEW CONCURRENTLY;"), 17).isEmpty());
    }

    /**
     * Each statement is classified independently, with its source span.
     */
    @Test
    void classifiesEachStatement() {
        String sql = "CREATE SCHEMA x; CREATE INDEX CONCURRENTLY idx ON t (c);";

        List<Classification> classifications = TransactionForbiddenClassifier.classify(
                SqlLexer.tokenize(sql), SqlStatementSplitter.split(sql), 17);

        assertEquals(1, classifications.size());
        assertEquals(Family.CREATE_INDEX_CONCURRENTLY, classifications.get(0).family());
        assertEquals("CREATE INDEX CONCURRENTLY idx ON t (c)", classifications.get(0).statement().text());
    }

    /**
     * Empty input and comment-only input yield no classifications.
     */
    @Test
    void handlesEmptyInput() {
        assertTrue(families("", 17).isEmpty());
        assertTrue(families("-- CREATE INDEX CONCURRENTLY idx ON t (c);", 17).isEmpty());
    }
}
