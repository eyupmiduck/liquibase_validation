-- Parse-only fixture exercising uppercase PUBLIC, a procedure and mixed case.
revoke execute on procedure grants.p(integer) from PUBLIC;
grant execute on procedure grants.p(integer) to app_caller;
