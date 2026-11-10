-- ============================================================================
-- AeroSync Stage 3 - user accounts and airport-scoped access.
--
-- Adds two tables; no existing table or row is changed.
--   * app_users      one row per person; BCrypt password hash, never plaintext
--   * user_airports  which airports a user may operate (many-to-many)
--
-- "app_users" avoids the reserved word USER. Usernames are stored lower-case
-- (the application normalises them) and are unique.
-- No accounts are created here: development demo accounts come from the
-- opt-in DemoUserSeeder (see application.properties), real accounts are
-- provisioned by an administrator.
-- ============================================================================

CREATE TABLE app_users (
    user_id        UUID         NOT NULL,
    username       VARCHAR(64)  NOT NULL,
    display_name   VARCHAR(120) NOT NULL,
    password_hash  VARCHAR(100) NOT NULL,
    role           VARCHAR(30)  NOT NULL,
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_login_at  TIMESTAMPTZ,
    CONSTRAINT pk_app_users PRIMARY KEY (user_id),
    CONSTRAINT uk_app_users_username UNIQUE (username),
    CONSTRAINT ck_app_users_username CHECK (username = lower(username) AND length(username) >= 3),
    CONSTRAINT ck_app_users_role CHECK (role IN ('OPERATIONS_CONTROLLER', 'OPERATIONS_VIEWER')),
    -- BCrypt hashes are always 60 characters; rejects accidental plaintext.
    CONSTRAINT ck_app_users_password_hash CHECK (length(password_hash) = 60)
);

COMMENT ON TABLE app_users IS 'AeroSync user accounts. Passwords are stored as BCrypt hashes only.';
COMMENT ON COLUMN app_users.role IS 'OPERATIONS_CONTROLLER (view and act) or OPERATIONS_VIEWER (view only).';

CREATE TABLE user_airports (
    user_id     UUID NOT NULL,
    airport_id  UUID NOT NULL,
    CONSTRAINT pk_user_airports PRIMARY KEY (user_id, airport_id),
    CONSTRAINT fk_user_airports_user FOREIGN KEY (user_id) REFERENCES app_users (user_id) ON DELETE CASCADE,
    CONSTRAINT fk_user_airports_airport FOREIGN KEY (airport_id) REFERENCES airports (airport_id)
);

CREATE INDEX idx_user_airports_airport ON user_airports (airport_id);

COMMENT ON TABLE user_airports IS 'Airports a user is authorized to operate; a user only sees and changes data of these airports.';
