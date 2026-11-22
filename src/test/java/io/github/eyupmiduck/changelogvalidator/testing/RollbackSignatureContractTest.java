package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.BeforeAll;

/**
 * Runs the shared {@link RollbackSignatureContract} against a probe routine
 * created in this class's private database and the rollback fixture that drops
 * it, so the contract's inherited test and its profile plumbing are exercised
 * here too.
 */
class RollbackSignatureContractTest extends RollbackSignatureContract {

    /**
     * The library's default database with {@code public} as the routine schema
     * and the fixture rollback directory.
     */
    private static final ProjectProfile PROFILE = ProjectProfile.builder(Configuration.DEFAULT)
            .managedSchemas(PUBLIC_SCHEMA)
            .routineSchemas(PUBLIC_SCHEMA)
            .routineRollbackDirectories("rollback-fixture")
            .build();

    /**
     * Creates the probe routine the fixture rollback drops.
     */
    @BeforeAll
    void createProbeRoutine() {
        dsl.execute("""
                CREATE FUNCTION public.probe_routine(integer)
                RETURNS integer
                LANGUAGE sql
                IMMUTABLE
                AS 'SELECT $1'
                """);
    }

    /**
     * The library has no project image or roles, so it uses the shared defaults
     * plus the probe routine's rollback directory.
     *
     * @return the profile
     */
    @Override
    protected ProjectProfile projectProfile() {
        return PROFILE;
    }
}
