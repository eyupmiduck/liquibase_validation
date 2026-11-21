-- Parse-only negative fixture: intentionally rolls back only grants.a so the
-- rollback-parity check reports the missing grants.b against
-- grants-forward-extra.sql. Do not "complete" it; it is not executed against
-- PostgreSQL.
REVOKE EXECUTE ON FUNCTION grants.a(integer) FROM app_caller;
GRANT EXECUTE ON FUNCTION grants.a(integer) TO public;
