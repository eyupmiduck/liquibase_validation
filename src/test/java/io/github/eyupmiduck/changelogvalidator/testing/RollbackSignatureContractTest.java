package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.BeforeAll;

import java.util.Collection;
import java.util.List;

/**
 * Runs the shared {@link RollbackSignatureContract} against a probe routine
 * created in this class's private database and the rollback fixture that drops
 * it, so the contract's inherited test and its hooks are exercised here too.
 */
class RollbackSignatureContractTest extends RollbackSignatureContract {

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
     * The library has no project image or roles, so it uses the shared defaults.
     *
     * @return the default configuration
     */
    @Override
    protected Configuration configuration() {
        return Configuration.DEFAULT;
    }

    /**
     * The probe routine lives in the public schema.
     *
     * @return the routine schemas
     */
    @Override
    protected Collection<String> routineSchemas() {
        return List.of(PUBLIC_SCHEMA);
    }

    /**
     * The fixture holds the probe routine's rollback.
     *
     * @return the rollback directories
     */
    @Override
    protected Collection<String> rollbackDirectories() {
        return List.of("rollback-fixture");
    }
}
