-- Parse-only negative fixture: intentionally omits DROP AGGREGATE for
-- routine_parity.probe_sum(integer), because the rollback parity check ignores
-- aggregates (prokind 'a'). Do not "complete" it; it is not executed against
-- PostgreSQL.
DO
$$
    BEGIN
        IF EXISTS (SELECT 1 FROM pg_catalog.pg_namespace WHERE nspname = 'routine_parity') THEN
            DROP FUNCTION IF EXISTS routine_parity.probe(integer);
        END IF;
    END
$$;
