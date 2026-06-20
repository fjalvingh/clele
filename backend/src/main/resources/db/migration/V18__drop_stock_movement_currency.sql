-- Currency is no longer stored per movement; the application uses a single app-wide currency
-- (configured via app.currency.* — see AppProperties / GET /api/settings).
ALTER TABLE stock_movement DROP COLUMN currency;
