-- Parse-only fixture for the negative PUBLIC-restore branch: paired with
-- grants-forward-both.sql, it restores PUBLIC only for grants.a. It is not
-- executed against PostgreSQL.
REVOKE EXECUTE ON FUNCTION grants.a(integer) FROM app_caller;
REVOKE EXECUTE ON FUNCTION grants.b(integer) FROM app_caller;
GRANT EXECUTE ON FUNCTION grants.a(integer) TO public;
