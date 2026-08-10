CREATE TABLE tenant (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    slug        VARCHAR(100) NOT NULL UNIQUE,
    status      VARCHAR(30)  NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

ALTER TABLE app_user
    ADD COLUMN tenant_id  BIGINT,
    ADD COLUMN full_name  VARCHAR(200),
    ADD COLUMN role       VARCHAR(30) NOT NULL DEFAULT 'TENANT_ADMIN',
    ADD COLUMN enabled    BOOLEAN     NOT NULL DEFAULT TRUE,
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE app_user
    ADD CONSTRAINT fk_app_user_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenant(id);

ALTER TABLE app_user DROP CONSTRAINT IF EXISTS app_user_email_key;

ALTER TABLE app_user
    ADD CONSTRAINT uq_app_user_tenant_email UNIQUE (tenant_id, email);

CREATE INDEX idx_app_user_tenant ON app_user (tenant_id);