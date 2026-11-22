-- Parse-only negative fixture: grants grants.b to app_caller's rollback even
-- though the forward never granted it. It is not executed against PostgreSQL.
REVOKE EXECUTE ON FUNCTION grants.a(integer) FROM app_caller;
GRANT EXECUTE ON FUNCTION grants.a(integer) TO public;
REVOKE EXECUTE ON FUNCTION grants.b(integer) FROM app_caller;
