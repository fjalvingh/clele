CREATE TABLE tag (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

-- Case-independent tag names: "SMD" and "smd" are the same tag.
CREATE UNIQUE INDEX tag_name_ci_idx ON tag (LOWER(name));

CREATE TABLE part_tag (
    part_id BIGINT NOT NULL REFERENCES part(id) ON DELETE CASCADE,
    tag_id BIGINT NOT NULL REFERENCES tag(id) ON DELETE CASCADE,
    PRIMARY KEY (part_id, tag_id)
);

CREATE INDEX part_tag_tag_id_idx ON part_tag (tag_id);
