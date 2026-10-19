-- V63: Ordering parts.
--
-- Three layers, from demand to delivery:
--   order_item          the order list: "we need N of this part" (optionally for a project). An
--                       item is on the list while purchase_order_line_id is NULL; adding it to an
--                       order links it to that order's line for the part.
--   purchase_order      one order at one supplier. DRAFT while it is being matched with the
--                       supplier (export, upload, import their file back), ORDERED once placed,
--                       RECEIVED when everything came in or it was closed, or CANCELLED.
--   purchase_order_line what is actually ordered: one row per (order, part). The supplier's file
--                       replaces these lines' quantities and prices; receiving books them to stock.
--
-- supplier and part_supplier remember the distributors an organisation buys from and each one's
-- own number for a part, so a later order can carry it without looking it up again.

CREATE TABLE supplier (
    id              BIGSERIAL    PRIMARY KEY,
    organisation_id BIGINT       NOT NULL REFERENCES organisation(id) ON DELETE CASCADE,
    name            VARCHAR(100) NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_supplier_name UNIQUE (organisation_id, name)
);

-- Every organisation starts with Mouser, the supplier the order file format was built around.
INSERT INTO supplier (organisation_id, name)
SELECT id, 'Mouser' FROM organisation;

CREATE TABLE part_supplier (
    id                   BIGSERIAL      PRIMARY KEY,
    part_id              BIGINT         NOT NULL REFERENCES part(id) ON DELETE CASCADE,
    supplier_id          BIGINT         NOT NULL REFERENCES supplier(id) ON DELETE CASCADE,
    supplier_part_number VARCHAR(64),
    last_unit_price      NUMERIC(14, 5),
    updated_at           TIMESTAMP      NOT NULL DEFAULT now(),
    CONSTRAINT uq_part_supplier UNIQUE (part_id, supplier_id)
);

CREATE INDEX idx_part_supplier_supplier ON part_supplier(supplier_id);

CREATE TABLE purchase_order (
    id                 BIGSERIAL   PRIMARY KEY,
    organisation_id    BIGINT      NOT NULL REFERENCES organisation(id) ON DELETE CASCADE,
    supplier_id        BIGINT      NOT NULL REFERENCES supplier(id),
    status             VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    supplier_reference VARCHAR(64),
    notes              TEXT,
    created_by_id      BIGINT      REFERENCES app_user(id) ON DELETE SET NULL,
    created_at         TIMESTAMP   NOT NULL,
    updated_at         TIMESTAMP   NOT NULL,
    ordered_at         TIMESTAMP,
    received_at        TIMESTAMP
);

CREATE INDEX idx_purchase_order_org ON purchase_order(organisation_id);

CREATE TABLE purchase_order_line (
    id                   BIGSERIAL      PRIMARY KEY,
    purchase_order_id    BIGINT         NOT NULL REFERENCES purchase_order(id) ON DELETE CASCADE,
    part_id              BIGINT         NOT NULL REFERENCES part(id),
    quantity             INT            NOT NULL,
    unit_price           NUMERIC(14, 5),
    supplier_part_number VARCHAR(64),
    qty_received         INT            NOT NULL DEFAULT 0,
    CONSTRAINT uq_purchase_order_line UNIQUE (purchase_order_id, part_id)
);

CREATE TABLE order_item (
    id                     BIGSERIAL      PRIMARY KEY,
    organisation_id        BIGINT         NOT NULL REFERENCES organisation(id) ON DELETE CASCADE,
    -- A wish for a part that no longer exists is no wish at all. An ordered line is different: it
    -- is history, so purchase_order_line keeps a plain FK and PartService refuses the delete.
    part_id                BIGINT         NOT NULL REFERENCES part(id) ON DELETE CASCADE,
    quantity               INT            NOT NULL,
    unit_price             NUMERIC(14, 5),
    project_id             BIGINT         REFERENCES project(id) ON DELETE SET NULL,
    purchase_order_line_id BIGINT         REFERENCES purchase_order_line(id) ON DELETE SET NULL,
    notes                  TEXT,
    created_by_id          BIGINT         REFERENCES app_user(id) ON DELETE SET NULL,
    created_at             TIMESTAMP      NOT NULL,
    updated_at             TIMESTAMP      NOT NULL
);

CREATE INDEX idx_order_item_org ON order_item(organisation_id);
CREATE INDEX idx_order_item_line ON order_item(purchase_order_line_id);

-- Receiving an order writes PURCHASE movements; this says which order they came from.
ALTER TABLE stock_movement
    ADD COLUMN purchase_order_id BIGINT REFERENCES purchase_order(id) ON DELETE SET NULL;

-- Distributor prices of small parts run to fractions of a cent (a resistor at EUR 0.0034), and stock
-- prices were held to two decimals: receiving such an order would have booked the parts at 0.00.
-- Widen every stock price column to the precision the order tables use.
ALTER TABLE stock_entry          ALTER COLUMN unit_price        TYPE NUMERIC(14, 5);
ALTER TABLE stock_movement       ALTER COLUMN unit_price        TYPE NUMERIC(14, 5);
ALTER TABLE part_kit_generation  ALTER COLUMN unit_price        TYPE NUMERIC(14, 5);
ALTER TABLE part_kit_generation_item ALTER COLUMN unit_price_before TYPE NUMERIC(14, 5);
