ALTER TABLE auth.app_user
    ADD COLUMN mfa_changed_at TIMESTAMPTZ;
ALTER TABLE auth.refresh_session
    ADD COLUMN amr VARCHAR(40) NOT NULL DEFAULT 'pwd';
CREATE TABLE auth.mfa_config
(
    username         VARCHAR(64) PRIMARY KEY REFERENCES auth.app_user (username),
    enabled          BOOLEAN NOT NULL DEFAULT false,
    encrypted_secret TEXT,
    pending_secret   TEXT,
    pending_expires  TIMESTAMPTZ,
    last_step        BIGINT  NOT NULL DEFAULT -1,
    failures         INTEGER NOT NULL DEFAULT 0,
    blocked_until    TIMESTAMPTZ
);
CREATE TABLE auth.mfa_recovery_code
(
    username    VARCHAR(64) NOT NULL REFERENCES auth.app_user (username),
    code_hash   VARCHAR(64) NOT NULL,
    consumed_at TIMESTAMPTZ,
    PRIMARY KEY (username, code_hash)
);
CREATE TABLE auth.mfa_challenge
(
    token_hash  VARCHAR(64) PRIMARY KEY,
    username    VARCHAR(64) NOT NULL REFERENCES auth.app_user (username),
    expires_at  TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ
);
CREATE INDEX mfa_challenge_user ON auth.mfa_challenge (username);
