-- Parse-only fixture for the stale-rollback negative case: grants-rollback-stale.sql
-- revokes grants.b, which the forward never grants. It is not executed against
-- PostgreSQL.
REVOKE EXECUTE ON FUNCTION grants.a(integer) FROM public;
GRANT EXECUTE ON FUNCTION grants.a(integer) TO app_caller;
