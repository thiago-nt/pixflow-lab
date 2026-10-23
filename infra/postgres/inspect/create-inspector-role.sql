SELECT format('CREATE ROLE pixflow_inspector LOGIN PASSWORD %L', :'inspector_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pixflow_inspector')
\gexec

SELECT format('ALTER ROLE pixflow_inspector PASSWORD %L', :'inspector_password')
\gexec

ALTER ROLE pixflow_inspector SET default_transaction_read_only = on;
ALTER ROLE pixflow_inspector NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;

SELECT format('GRANT CONNECT ON DATABASE %I TO pixflow_inspector', :'database_name')
\gexec
GRANT USAGE ON SCHEMA public TO pixflow_inspector;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO pixflow_inspector;
GRANT SELECT ON ALL SEQUENCES IN SCHEMA public TO pixflow_inspector;

SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public GRANT SELECT ON TABLES TO pixflow_inspector', :'owner_name')
\gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public GRANT SELECT ON SEQUENCES TO pixflow_inspector', :'owner_name')
\gexec
