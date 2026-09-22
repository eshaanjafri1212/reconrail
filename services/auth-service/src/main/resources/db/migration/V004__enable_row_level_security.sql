-- closing the gap left by Hibernate filters (which do not apply to find-by-primary-key or native queries).
ALTER TABLE app_user        ENABLE ROW LEVEL SECURITY;
ALTER TABLE refresh_token   ENABLE ROW LEVEL SECURITY;

-- Without FORCE, PostgreSQL exempts the table OWNER from its own policies.  without this line RLS would silently have no effect.
ALTER TABLE app_user      FORCE ROW LEVEL SECURITY;
ALTER TABLE refresh_token FORCE ROW LEVEL SECURITY;


CREATE POLICY tenant_isolation_app_user ON app_user
    USING (tenant_id = NULLIF(current_setting('app.tenant_Id', true), '')::bigint);

CREATE POLICY tenant_isolation_refresh_token ON refresh_token
    USING (tenant_id = NULLIF(current_setting('app.tenant_Id', true), '')::bigint);