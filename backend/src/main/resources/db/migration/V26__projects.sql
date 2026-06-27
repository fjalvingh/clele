-- V26: Add projects feature.
-- A project tracks a build (e.g. 5 controller boards) that pulls parts from stock.
-- States: PLANNING (soft BOM reservation) → BUILDING (stock pulled) → COMPLETED or CANCELLED.

-- 1. Extend stock_movement with a nullable project FK (constraint added after project table exists).
ALTER TABLE stock_movement
    ADD COLUMN project_id BIGINT;

-- 2. The project itself.
CREATE TABLE project (
    id             BIGSERIAL    PRIMARY KEY,
    name           VARCHAR(255) NOT NULL,
    description    TEXT,
    status         VARCHAR(20)  NOT NULL DEFAULT 'PLANNING',
    instance_count INT          NOT NULL DEFAULT 1,
    owner_id       BIGINT       NOT NULL REFERENCES app_user(id),
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP    NOT NULL
);

CREATE INDEX idx_project_owner_id ON project(owner_id);

-- 3. BOM: one row per (project, part), stating qty needed per build instance.
CREATE TABLE project_part (
    id               BIGSERIAL PRIMARY KEY,
    project_id       BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    part_id          BIGINT NOT NULL REFERENCES part(id),
    qty_per_instance INT    NOT NULL DEFAULT 1,
    notes            TEXT,
    CONSTRAINT uq_project_part UNIQUE (project_id, part_id)
);

CREATE INDEX idx_project_part_project_id ON project_part(project_id);

-- 4. Stock inside the project: parts physically pulled from a location.
--    location_id remembers where the part came from so it can be returned on cancel.
CREATE TABLE project_stock (
    id          BIGSERIAL     PRIMARY KEY,
    project_id  BIGINT        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    part_id     BIGINT        NOT NULL REFERENCES part(id),
    location_id BIGINT        NOT NULL REFERENCES location(id),
    quantity    INT           NOT NULL,
    unit_price  DECIMAL(10,4),
    movement_id BIGINT        REFERENCES stock_movement(id) ON DELETE SET NULL,
    added_at    TIMESTAMP     NOT NULL,
    added_by_id BIGINT        REFERENCES app_user(id) ON DELETE SET NULL
);

CREATE INDEX idx_project_stock_project_id ON project_stock(project_id);

-- 5. Now wire the FK from stock_movement back to project.
ALTER TABLE stock_movement
    ADD CONSTRAINT fk_stock_movement_project
        FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE SET NULL;

CREATE INDEX idx_stock_movement_project_id ON stock_movement(project_id);
