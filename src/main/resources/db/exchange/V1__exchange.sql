-- The exchange's order book and trade tape. An order is named by its member's clientOrderId;
-- executions are whole (the simulated market is deep), so an order has at most one trade.

CREATE TABLE orders (
    id               uuid PRIMARY KEY,
    member           text NOT NULL,
    client_order_id  text NOT NULL,
    request_hash     text NOT NULL,
    symbol           text NOT NULL,
    side             text NOT NULL CHECK (side IN ('BUY', 'SELL')),
    type             text NOT NULL CHECK (type IN ('MARKET', 'LIMIT')),
    quantity         int NOT NULL CHECK (quantity > 0),
    limit_paise      bigint CHECK (limit_paise > 0),
    protection_paise bigint CHECK (protection_paise > 0),
    status           text NOT NULL CHECK (status IN ('OPEN', 'FILLED', 'CANCELLED', 'EXPIRED')),
    filled_quantity  int NOT NULL DEFAULT 0,
    price_paise      bigint,
    trade_id         uuid,
    reason           text,
    session_date     date NOT NULL,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    UNIQUE (member, client_order_id),
    CHECK ((type = 'LIMIT') = (limit_paise IS NOT NULL)),
    CHECK ((status = 'FILLED') = (trade_id IS NOT NULL AND price_paise IS NOT NULL AND filled_quantity = quantity))
);

CREATE INDEX orders_open ON orders (symbol) WHERE status = 'OPEN';

CREATE TABLE trades (
    id           uuid PRIMARY KEY,
    order_id     uuid NOT NULL UNIQUE REFERENCES orders (id),
    member       text NOT NULL,
    symbol       text NOT NULL,
    side         text NOT NULL,
    quantity     int NOT NULL,
    price_paise  bigint NOT NULL,
    executed_at  timestamptz NOT NULL
);

-- What members must be told, delivered until they acknowledge (same design as Sprout Bank's).
CREATE TABLE outbox (
    id              uuid PRIMARY KEY,
    member          text NOT NULL,
    callback_url    text NOT NULL,
    body            text NOT NULL,
    created_at      timestamptz NOT NULL,
    next_attempt_at timestamptz NOT NULL,
    attempts        int NOT NULL DEFAULT 0,
    delivered_at    timestamptz,
    last_error      text
);

CREATE INDEX outbox_due ON outbox (next_attempt_at) WHERE delivered_at IS NULL;
