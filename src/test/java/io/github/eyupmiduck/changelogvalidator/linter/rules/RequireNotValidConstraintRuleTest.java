package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.Rule;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link RequireNotValidConstraintRule}: a validating CHECK or FOREIGN
 * KEY on an existing table is reported, NOT VALID and a same-changeset
 * CREATE TABLE are accepted, and other constraint kinds are ignored.
 */
class RequireNotValidConstraintRuleTest {

    private static List<Rule.Violation> check(String sql) {
        return new RequireNotValidConstraintRule().check(RuleTestSupport.forwardOnly(sql));
    }

    /**
     * A CHECK or FOREIGN KEY added to an existing table without NOT VALID is
     * reported; NOT VALID is accepted.
     */
    @Test
    void reportsValidatingCheckAndForeignKey() {
        assertEquals(1, check("ALTER TABLE public.t ADD CONSTRAINT c CHECK (x > 0)").size());
        assertTrue(check("ALTER TABLE public.t ADD CONSTRAINT c CHECK (x > 0) NOT VALID").isEmpty());
        assertEquals(1, check("ALTER TABLE public.t ADD FOREIGN KEY (x) REFERENCES other (id)").size());
        assertTrue(check("ALTER TABLE public.t ADD FOREIGN KEY (x) REFERENCES other (id) NOT VALID").isEmpty());
        assertEquals(Severity.WARNING, new RequireNotValidConstraintRule().defaultSeverity());
        assertTrue(!new RequireNotValidConstraintRule().enabledByDefault());
    }

    /**
     * Constraint kinds PostgreSQL cannot add as NOT VALID are not reported.
     */
    @Test
    void ignoresUniquesPrimaryKeysAndExclusions() {
        assertTrue(check("ALTER TABLE t ADD CONSTRAINT u UNIQUE (x)").isEmpty());
        assertTrue(check("ALTER TABLE t ADD CONSTRAINT p PRIMARY KEY (x)").isEmpty());
        assertTrue(check("ALTER TABLE t ADD CONSTRAINT e EXCLUDE USING gist (x WITH =)").isEmpty());
        assertTrue(check("ALTER TABLE t ADD COLUMN c integer").isEmpty());
    }

    /**
     * A table created in the same direction is fresh and needs no NOT VALID.
     */
    @Test
    void exemptsATableCreatedInTheSameChangeset() {
        assertTrue(check("CREATE TABLE t (x integer);"
                + " ALTER TABLE t ADD CONSTRAINT c CHECK (x > 0)").isEmpty());
        assertEquals(1, check("CREATE TABLE other (x integer);"
                + " ALTER TABLE t ADD CONSTRAINT c CHECK (x > 0)").size());
    }

    /**
     * A validating constraint in a later ADD clause is still reported; an earlier
     * clause that is not a CHECK/FOREIGN KEY must not abandon the statement.
     */
    @Test
    void reportsAConstraintInALaterAddClause() {
        assertEquals(1, check("ALTER TABLE t ADD COLUMN y integer,"
                + " ADD CONSTRAINT c CHECK (y > 0)").size());
        assertEquals(1, check("ALTER TABLE t ADD PRIMARY KEY (id),"
                + " ADD CONSTRAINT c CHECK (y > 0)").size());
        assertTrue(check("ALTER TABLE t ADD COLUMN y integer,"
                + " ADD CONSTRAINT c CHECK (y > 0) NOT VALID").isEmpty());
    }

    /**
     * A NOT VALID in a later clause must not exempt an earlier validating
     * constraint, and an in-expression `NOT valid` pair is not the NOT VALID
     * modifier.
     */
    @Test
    void boundsNotValidToItsOwnAddClause() {
        assertEquals(1, check("ALTER TABLE t ADD CONSTRAINT c1 CHECK (x > 0),"
                + " ADD CONSTRAINT c2 CHECK (y > 0) NOT VALID").size());
        assertEquals(1, check("ALTER TABLE t ADD CONSTRAINT c CHECK (NOT valid)").size());
    }

    /**
     * The rollback direction is checked against its own created tables.
     */
    @Test
    void checksRollback() {
        assertEquals(1, new RequireNotValidConstraintRule().check(RuleTestSupport.context(null,
                "ALTER TABLE t ADD CONSTRAINT c CHECK (x > 0)")).size());
        assertTrue(new RequireNotValidConstraintRule().check(RuleTestSupport.context(null,
                "ALTER TABLE t ADD CONSTRAINT c CHECK (x > 0) NOT VALID")).isEmpty());
    }

    /**
     * A source scoped to another database is ignored.
     */
    @Test
    void ignoresAnotherDatabase() {
        assertTrue(new RequireNotValidConstraintRule().check(new io.github.eyupmiduck.changelogvalidator.linter.RuleContext(
                RuleTestSupport.changeSet(true, false, null, false),
                List.of(RuleTestSupport.unit("ALTER TABLE t ADD CONSTRAINT c CHECK (x > 0)", "oracle")),
                List.of())).isEmpty());
    }
}
