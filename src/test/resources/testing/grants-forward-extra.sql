-- Parse-only fixture for the negative caller-revoke branch: grants-rollback-one.sql
-- omits grants.b. It is not executed against PostgreSQL.
REVOKE EXECUTE ON FUNCTION grants.a(integer) FROM public;
GRANT EXECUTE ON FUNCTION grants.a(integer) TO app_caller;
GRANT EXECUTE ON FUNCTION grants.b(integer) TO app_caller;
