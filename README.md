# sprout-exchange

The **Sprout Stock Exchange (SSX)**: a simulated stock exchange, part of "the outside world" beside
Sprout Bank. Brokers (members) send it orders; it executes them against the simulated market from
[sprout-marketdata](https://github.com/SaiNayakk/sprout-marketdata) and tells the member what happened.

- **Trading hours** follow the market: orders are accepted only while it is open, and orders still open at the close expire.
- **A deep market.** Every execution is for the whole quantity, at the last traded price. `MARKET` orders execute at once (or are cancelled if the price is already beyond their protection price); `LIMIT` orders rest until the price reaches them.
- **Exchange rules.** Limit prices must be on the instrument's tick and within the day's band (20% either side of the previous close).
- **Exactly once.** An order is filled, cancelled or expired in one transaction with its row locked, together with the trade and the member's callback, so racing cancels and fills change an order once and the member always hears.
- **Callbacks** are signed (HMAC-SHA256) and delivered from an outbox until the member acknowledges, backing off to a minute apart. A member that was down gets every event when it comes back.
- **No guessing.** Without fresh prices (market data unreachable for 10 s) it refuses new orders and pauses matching.

Prices are polled from market data rather than read from the NATS tick stream: the exchange needs the
latest price, not every tick, and this way it knows when it can't trust what it has.

**Clients and the trade tape.** Orders carry the member's client code (without one, the trade is the
member's own: `PRO`), execution reports name their session, and the clearing corporation reads each
session's trades from `/clearing/v1/trades` to settle them T+1.

## Part of Sprout

[Sprout](https://sainayakk.github.io/sprout-platform/) is a simulated brokerage built from scratch as
separate services, each with its own repository and contract. Architecture, environments and test
evidence live in [sprout-platform](https://github.com/SaiNayakk/sprout-platform); this service's API is
[`exchange-v1.yaml`](https://github.com/SaiNayakk/sprout-contracts/blob/main/src/main/resources/sprout/contracts/openapi/exchange-v1.yaml)
in sprout-contracts. It runs inside the **street** host.

`./mvnw verify` runs the tests on a real Postgres (Docker needed), every JSON response checked against
the contract.

## License

MIT
