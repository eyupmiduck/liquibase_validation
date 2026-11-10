package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;

/**
 * Verifies that the routine/rollback parity check ignores aggregates, which
 * require {@code DROP AGGREGATE} rather than the {@code DROP FUNCTION} a
 * rollback file contains.
 */
class RoutineAssertionsAggregateTest extends PostgresTestBase {

    /**
     * An aggregate in the checked schema does not make a correct routine rollback
     * look stale.
     *
     * @throws Exception if the fixture cannot be created or checked
     */
    @Test
    void ignoresAggregatesInRollbackParity() throws Exception {
        dsl.execute("CREATE SCHEMA routine_parity");
        try {
            dsl.execute("""
                    CREATE FUNCTION routine_parity.probe(integer)
                    RETURNS integer
                    LANGUAGE sql
                    IMMUTABLE
                    AS 'SELECT $1'
                    """);
            dsl.execute("""
                    CREATE AGGREGATE routine_parity.probe_sum(integer)
                    (SFUNC = int4pl, STYPE = integer)
                    """);

            try (Connection owner = openOwnerConnection()) {
                RoutineAssertions.assertRollbackDropsMatchRoutines(
                        owner, List.of("routine_parity"), List.of("rollback-aggregate-fixture"));
            }
        } finally {
            dsl.execute("DROP SCHEMA IF EXISTS routine_parity CASCADE");
        }
    }
}
