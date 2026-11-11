DO
$$
    BEGIN
        IF EXISTS (SELECT 1 FROM pg_catalog.pg_namespace WHERE nspname = 'routine_parity') THEN
            DROP FUNCTION IF EXISTS routine_parity.probe(integer);
        END IF;
    END
$$;
