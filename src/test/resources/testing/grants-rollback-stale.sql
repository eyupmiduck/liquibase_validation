-- Parse-only negative fixture: revokes grants.b from app_caller even though the
-- forward (grants-forward-stale.sql) never granted it, so the rollback-parity
-- check reports the stale entry. Do not "complete" it; it is not executed
-- against PostgreSQL.
REVOKE EXECUTE ON FUNCTION grants.a(integer) FROM app_caller;
GRANT EXECUTE ON FUNCTION grants.a(integer) TO public;
REVOKE EXECUTE ON FUNCTION grants.b(integer) FROM app_caller;
