-- Images on a part kit template, handed to every part it generates.
--
-- A kit's parts look identical — thirty resistor values photographed once. Since V46 an attachment
-- is shared content rather than one part's property, so the template holds *links* to the same
-- part_attachment rows the parts do: generating links each new part to the very same row, and one
-- photo serves the whole kit.
--
-- Deliberately the same shape as part_attachment_link rather than a second copy of the bytes, and
-- deliberately a separate table rather than a nullable template_id on that link: a link belongs to
-- exactly one owner, and a column that is sometimes a part and sometimes a template is a column
-- every query has to remember to filter.
--
-- No organisation_id: it reaches one through template_id, exactly as part_attachment_link does
-- through part_id.

CREATE TABLE part_kit_template_attachment (
    id            BIGSERIAL PRIMARY KEY,
    template_id   BIGINT    NOT NULL REFERENCES part_kit_template (id) ON DELETE CASCADE,
    attachment_id BIGINT    NOT NULL REFERENCES part_attachment (id) ON DELETE CASCADE,
    display_order INTEGER   NOT NULL DEFAULT 0,
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT part_kit_template_attachment_unique UNIQUE (template_id, attachment_id)
);

CREATE INDEX idx_part_kit_template_attachment_template ON part_kit_template_attachment (template_id);
CREATE INDEX idx_part_kit_template_attachment_attachment ON part_kit_template_attachment (attachment_id);
