REVOKE EXECUTE ON FUNCTION grants.a(integer) FROM public;
GRANT EXECUTE ON FUNCTION grants.a(integer) TO app_caller;
REVOKE EXECUTE ON FUNCTION grants.b(integer) FROM public;
GRANT EXECUTE ON FUNCTION grants.b(integer) TO app_caller;
