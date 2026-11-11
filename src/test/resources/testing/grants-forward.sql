-- Parse-only fixture for GrantAssertions; it is not executed against PostgreSQL.
REVOKE EXECUTE ON FUNCTION grants.a(integer) FROM public;
GRANT EXECUTE ON FUNCTION grants.a(integer) TO app_caller;
