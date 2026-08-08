-- Generating a kit becomes undoable: record what each run did, so it can be taken back.
--
-- "Generate parts" creates up to thirty parts and thirty stock movements in one click. Until now
-- the only way back was to find every one of them in the parts list and delete it by hand -- and
-- nothing in the catalogue said which parts came from which run. These two tables are that record.
--
-- The header is what the user entered (quantity per value, price, location) plus when and by whom;
-- the items are what it produced, one row per value, naming the part, whether the run *created* it
-- or merely found it, and the stock movement it wrote.
--
-- quantity_before / unit_price_before are the state of the stock entry immediately before the run
-- touched it, and they carry the whole undo contract:
--   * NULL quantity_before means no stock entry existed at that location -- undo removes the row
--     rather than setting it to zero.
--   * current quantity must still equal quantity_before + quantity_added, or the stock has moved
--     since and the run is no longer the last word on it. That is the "stock entries are still the
--     same as created" test, checked per item.
--   * unit_price_before restores the weighted-average cost the add recalculated. It cannot be
--     derived after the fact -- the WAC formula is not invertible once other movements exist.
--
-- Both foreign keys to the world outside are ON DELETE SET NULL rather than CASCADE: a part or a
-- movement deleted by some other route must leave the record standing and *visibly incomplete*,
-- because that is exactly the condition under which undoing is refused. A cascade would silently
-- delete the evidence and let the undo proceed against a half-vanished run.

CREATE TABLE part_kit_generation (
    id                 BIGSERIAL PRIMARY KEY,
    template_id        BIGINT    NOT NULL REFERENCES part_kit_template (id) ON DELETE CASCADE,

    -- What was asked for. The location may later be deleted or merged away; the movement rows
    -- (which a merge re-points) are what the undo actually reverses, so this is for display.
    location_id        BIGINT REFERENCES location (id) ON DELETE SET NULL,
    quantity_per_value INT       NOT NULL,
    unit_price         DECIMAL(10, 2),

    -- What it did, so a listing needs no aggregate over the items.
    parts_created      INT       NOT NULL,
    parts_found        INT       NOT NULL,
    stock_added        INT       NOT NULL,

    generated_by_id    BIGINT    NOT NULL REFERENCES app_user (id),
    generated_at       TIMESTAMP NOT NULL
);

CREATE INDEX idx_part_kit_generation_template ON part_kit_generation (template_id, generated_at DESC);

CREATE TABLE part_kit_generation_item (
    id                BIGSERIAL PRIMARY KEY,
    generation_id     BIGINT       NOT NULL REFERENCES part_kit_generation (id) ON DELETE CASCADE,

    -- The kit value this line came from, kept as text: the template's value list may change (or the
    -- value be removed) long after the run, and the record must still say what was generated.
    value             VARCHAR(255) NOT NULL,
    display_order     INT          NOT NULL,

    part_id           BIGINT REFERENCES part (id) ON DELETE SET NULL,
    -- True when this run created the part. Only those are deleted by an undo -- a part the run
    -- merely found and restocked existed before it and must survive it.
    part_created      BOOLEAN      NOT NULL,

    quantity_added    INT          NOT NULL,
    movement_id       BIGINT REFERENCES stock_movement (id) ON DELETE SET NULL,
    quantity_before   INT,
    unit_price_before DECIMAL(10, 2),

    CONSTRAINT uq_part_kit_generation_item UNIQUE (generation_id, value)
);

CREATE INDEX idx_part_kit_generation_item_generation ON part_kit_generation_item (generation_id);
CREATE INDEX idx_part_kit_generation_item_part ON part_kit_generation_item (part_id);
