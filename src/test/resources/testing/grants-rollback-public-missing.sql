-- Negative fixture: both caller grants are revoked, but PUBLIC is restored only
-- for grants.a, so the PUBLIC branch fails independently.
REVOKE EXECUTE ON FUNCTION grants.a(integer) FROM app_caller;
REVOKE EXECUTE ON FUNCTION grants.b(integer) FROM app_caller;
GRANT EXECUTE ON FUNCTION grants.a(integer) TO public;
