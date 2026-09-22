DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'reconrail_app') THEN
        CREATE ROLE reconrail_app
            LOGIN
            PASSWORD '${appDbPassword}'
            NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
    END IF;
END
$$;

GRANT CONNECT ON DATABASE authdb TO reconrail_app;
GRANT USAGE ON SCHEMA public TO reconrail_app;

-- Data access only: no DDL, no ownership.
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO reconrail_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO reconrail_app;

-- Tables created by FUTURE migrations get the same grants automatically.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO reconrail_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO reconrail_app;