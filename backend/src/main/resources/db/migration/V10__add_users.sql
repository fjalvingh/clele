-- User accounts + permissions for authentication/authorization.
-- NOTE: "user" is a reserved word in PostgreSQL, so the table is named app_user.

CREATE TABLE app_user (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name     VARCHAR(255),
    phone         VARCHAR(64),
    created_at    TIMESTAMP NOT NULL,
    updated_at    TIMESTAMP NOT NULL
);

-- One row per (user, permission). Permission values are plain strings used as Spring Security
-- authorities (e.g. PARTS_EDIT, USERS_EDIT).
CREATE TABLE app_user_permission (
    user_id    BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    permission VARCHAR(64) NOT NULL,
    PRIMARY KEY (user_id, permission)
);

-- Bootstrap admin so someone can log in and manage users.
-- Default credentials: admin@clele.local / admin  -- CHANGE THE PASSWORD AFTER FIRST LOGIN.
-- password_hash is a BCrypt hash of "admin".
INSERT INTO app_user (email, password_hash, full_name, phone, created_at, updated_at)
VALUES ('admin@clele.local',
        '$2a$10$GS8/CeoN.lpNYwMQsdokCeRjowa7bvp32nGtn3xnWVrsmpBLQqY3C',
        'Administrator', NULL, now(), now());

INSERT INTO app_user_permission (user_id, permission)
SELECT id, 'PARTS_EDIT' FROM app_user WHERE email = 'admin@clele.local'
UNION ALL
SELECT id, 'USERS_EDIT' FROM app_user WHERE email = 'admin@clele.local';
