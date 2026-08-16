CREATE TABLE refresh_token (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT      NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    tenant_id       BIGINT      NOT NULL REFERENCES tenant(id),
    token_hash      VARCHAR(64) NOT NULL UNIQUE,
    family_id       UUID        NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    revoked_at      TIMESTAMPTZ,
    replaced_by_id  BIGINT      REFERENCES refresh_token(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    user_agent      VARCHAR(300),
    ip_address      VARCHAR(45)
);

CREATE INDEX idx_refresh_token_hash    ON refresh_token (token_hash);
CREATE INDEX idx_refresh_token_family  ON refresh_token (family_id);
CREATE INDEX idx_refresh_token_user    ON refresh_token (tenant_id, user_id);