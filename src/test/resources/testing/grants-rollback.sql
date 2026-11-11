-- Parse-only fixture for GrantAssertions; it is not executed against PostgreSQL.
REVOKE EXECUTE ON FUNCTION grants.a(integer) FROM app_caller;
GRANT EXECUTE ON FUNCTION grants.a(integer) TO public;
