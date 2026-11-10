-- Parse-only matching rollback for grants-variants-forward.sql.
REVOKE EXECUTE ON PROCEDURE grants.p(integer) FROM app_caller;
GRANT EXECUTE ON PROCEDURE grants.p(integer) TO public;
