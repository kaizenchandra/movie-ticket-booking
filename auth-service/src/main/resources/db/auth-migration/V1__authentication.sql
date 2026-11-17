CREATE TABLE auth.app_user
(
    username      VARCHAR(64) PRIMARY KEY,
    password_hash VARCHAR(255) NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT true,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE auth.refresh_session
(
    id         UUID PRIMARY KEY,
    username   VARCHAR(64) NOT NULL REFERENCES auth.app_user (username),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked    BOOLEAN     NOT NULL DEFAULT false
);
CREATE INDEX refresh_session_user ON auth.refresh_session (username);
CREATE TABLE auth.refresh_token
(
    token_hash  VARCHAR(64) PRIMARY KEY,
    session_id  UUID NOT NULL REFERENCES auth.refresh_session (id) ON DELETE CASCADE,
    consumed_at TIMESTAMPTZ
);
CREATE INDEX refresh_token_session ON auth.refresh_token (session_id);
CREATE TABLE auth.signing_key
(
    id          VARCHAR(64) PRIMARY KEY,
    private_jwk TEXT NOT NULL
);
CREATE TABLE auth.login_attempt
(
    username      VARCHAR(64) PRIMARY KEY,
    failures      INTEGER NOT NULL DEFAULT 0,
    blocked_until TIMESTAMP
);
