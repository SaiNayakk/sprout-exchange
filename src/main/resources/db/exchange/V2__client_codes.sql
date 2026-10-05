-- Every order names the member's client (the UCC a real exchange requires), so the clearing
-- corporation can settle shares to the right demat account. Trades carry it, and their session.
ALTER TABLE orders ADD COLUMN client_code text NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE orders ALTER COLUMN client_code DROP DEFAULT;
ALTER TABLE trades ADD COLUMN client_code text NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE trades ALTER COLUMN client_code DROP DEFAULT;
ALTER TABLE trades ADD COLUMN session_date date;
UPDATE trades t SET session_date = o.session_date FROM orders o WHERE o.id = t.order_id;
ALTER TABLE trades ALTER COLUMN session_date SET NOT NULL;
CREATE INDEX trades_by_session ON trades (session_date, executed_at, id);
