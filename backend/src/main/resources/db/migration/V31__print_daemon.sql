CREATE TABLE print_daemon (
    id BIGSERIAL PRIMARY KEY,
    owner_id BIGINT REFERENCES app_user(id) ON DELETE SET NULL,
    name VARCHAR(255) NOT NULL,
    api_key_hash VARCHAR(100) NOT NULL,
    printer_ip VARCHAR(45),
    registered_ip VARCHAR(45) NOT NULL,
    last_seen_ip VARCHAR(45) NOT NULL,
    last_seen_at TIMESTAMP NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_print_daemon_owner ON print_daemon(owner_id);

CREATE TABLE print_job (
    id BIGSERIAL PRIMARY KEY,
    daemon_id BIGINT NOT NULL REFERENCES print_daemon(id) ON DELETE CASCADE,
    requested_by_id BIGINT NOT NULL REFERENCES app_user(id),
    label_png BYTEA NOT NULL,
    status VARCHAR(20) NOT NULL,
    error_message VARCHAR(1000),
    created_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP
);

CREATE INDEX idx_print_job_daemon ON print_job(daemon_id);

ALTER TABLE app_user ADD COLUMN print_method VARCHAR(20) NOT NULL DEFAULT 'BROWSER';
ALTER TABLE app_user ADD COLUMN preferred_daemon_id BIGINT REFERENCES print_daemon(id) ON DELETE SET NULL;
