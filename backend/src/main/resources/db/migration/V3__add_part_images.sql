CREATE TABLE part_image (
    id           BIGSERIAL PRIMARY KEY,
    part_id      BIGINT    NOT NULL REFERENCES part(id) ON DELETE CASCADE,
    display_order INT      NOT NULL DEFAULT 0,
    image_data   BYTEA     NOT NULL,
    created_at   TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_part_image_part_id ON part_image(part_id);
