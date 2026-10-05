package app.sprout.exchange.domain;

import app.sprout.exchange.config.ExchangeProperties;
import app.sprout.exchange.config.ExchangeProperties.Member;
import app.sprout.exchange.domain.Prices.Instrument;
import app.sprout.exchange.domain.Prices.Quote;
import app.sprout.exchange.domain.Prices.Snapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The order book and its matching against the simulated market.
 *
 * <p>Every change to an order happens in one transaction with its row locked, together with the
 * trade (for a fill) and the member's callback (in the outbox). So an order is filled, cancelled or
 * expired exactly once, and the member always hears about it.
 */
@Service
public class Exchange {

    private static final Logger log = LoggerFactory.getLogger(Exchange.class);

    public enum Side { BUY, SELL }

    public enum Type { MARKET, LIMIT }

    public record NewOrder(String clientOrderId, String clientCode, String symbol, Side side, Type type, int quantity, Long limitPaise,
                           Long protectionPaise) {}

    public record Order(UUID id, String member, String clientOrderId, String clientCode, String symbol, Side side, Type type, int quantity,
                        Long limitPaise, Long protectionPaise, String status, int filledQuantity, Long pricePaise,
                        UUID tradeId, String reason, LocalDate sessionDate, Instant createdAt, Instant updatedAt) {}

    public record Placed(Order order, boolean created) {}

    /** One execution, as the clearing corporation reads it. */
    public record Trade(UUID id, String member, String clientCode, String symbol, Side side, int quantity, long pricePaise,
                        LocalDate sessionDate, Instant executedAt) {}

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Prices prices;
    private final ExchangeProperties props;
    private final ObjectMapper json;

    public Exchange(JdbcClient db, TransactionTemplate tx, Clock clock, Prices prices, ExchangeProperties props, ObjectMapper json) {
        this.db = db;
        this.tx = tx;
        this.clock = clock;
        this.prices = prices;
        this.props = props;
        this.json = json;
    }

    // ── placing ──────────────────────────────────────────────────────────────

    public Placed place(Member member, NewOrder o) {
        validate(o);
        String hash = hash(o);
        Optional<Placed> earlier = existing(member, o.clientOrderId(), hash);
        if (earlier.isPresent()) {
            return earlier.get();
        }
        Snapshot market = prices.current().orElseThrow(() -> new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE,
                "The exchange has no current prices. Try again shortly.", 5, Map.of()));
        Instrument instrument = prices.instrument(o.symbol()).filter(Instrument::tradable)
                .orElseThrow(() -> new ApiException(ErrorCode.UNKNOWN_INSTRUMENT, "Nothing called " + o.symbol() + " trades here."));
        if (!market.open()) {
            throw new ApiException(ErrorCode.MARKET_CLOSED, "The market is closed. Orders are accepted from 09:15 to 15:30.");
        }
        Quote q = market.quotes().get(o.symbol());
        if (q == null) {
            throw new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "No price for " + o.symbol() + " yet.", 5, Map.of());
        }
        for (Long price : new Long[] {o.limitPaise(), o.protectionPaise()}) {
            if (price != null) {
                checkPrice(price, instrument, q);
            }
        }
        try {
            return tx.execute(s -> {
                UUID id = UUID.randomUUID();
                Instant now = clock.instant();
                db.sql("""
                                INSERT INTO orders (id, member, client_order_id, client_code, request_hash, symbol, side, type, quantity,
                                                    limit_paise, protection_paise, status, session_date, created_at, updated_at)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?)""")
                        .params(id, member.name(), o.clientOrderId(), o.clientCode(), hash, o.symbol(), o.side().name(), o.type().name(), o.quantity(),
                                o.limitPaise(), o.protectionPaise(), market.sessionDate(), ts(now), ts(now))
                        .update();
                Order order = lock(id);
                if (o.type() == Type.MARKET) {
                    if (beyondProtection(order, q.lastPaise())) {
                        finish(order, "CANCELLED", "The price had already moved beyond the protection price.");
                    } else {
                        fill(order, q.lastPaise());
                    }
                } else if (crosses(order, q.lastPaise())) {
                    fill(order, q.lastPaise());
                }
                return new Placed(order(id), true);
            });
        } catch (DuplicateKeyException e) {
            // the same order raced in and won
            return existing(member, o.clientOrderId(), hash).orElseThrow(() -> e);
        }
    }

    private void validate(NewOrder o) {
        if (o.clientOrderId() == null || o.clientOrderId().isBlank() || o.clientOrderId().length() > 64) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "clientOrderId is 1 to 64 characters.");
        }
        if (o.clientCode() != null && !o.clientCode().matches("[A-Za-z0-9-]{1,64}")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "clientCode (the member's code for its client) is 1 to 64 letters, digits or dashes.");
        }
        if (o.symbol() == null || o.side() == null || o.type() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "symbol, side and type are required.");
        }
        if (o.quantity() < 1 || o.quantity() > 1_000_000) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "quantity is between 1 and 10,00,000.");
        }
        if (o.type() == Type.LIMIT && o.limitPaise() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A LIMIT order needs a limitPrice.");
        }
        if (o.type() == Type.MARKET && o.limitPaise() != null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A MARKET order has no limitPrice.");
        }
        if (o.type() == Type.LIMIT && o.protectionPaise() != null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Only MARKET orders take a protectionPrice.");
        }
    }

    private void checkPrice(long paise, Instrument instrument, Quote q) {
        if (paise <= 0 || paise % instrument.tickPaise() != 0) {
            throw new ApiException(ErrorCode.INVALID_TICK, "Prices for " + instrument.symbol() + " move in steps of ₹"
                    + Money.rupees(instrument.tickPaise()) + ".");
        }
        long[] band = band(q.prevClosePaise(), instrument.tickPaise());
        if (paise < band[0] || paise > band[1]) {
            throw new ApiException(ErrorCode.PRICE_OUT_OF_BAND, "Today " + instrument.symbol() + " can trade between ₹"
                    + Money.rupees(band[0]) + " and ₹" + Money.rupees(band[1]) + ".");
        }
    }

    /** The day's allowed prices: the band around the previous close, rounded inwards to the tick. */
    long[] band(long prevClose, long tick) {
        long width = prevClose * props.bandPercent() / 100;
        long low = Math.max(tick, ((prevClose - width + tick - 1) / tick) * tick);
        long high = ((prevClose + width) / tick) * tick;
        return new long[] {low, high};
    }

    private Optional<Placed> existing(Member member, String clientOrderId, String hash) {
        return db.sql("SELECT id, request_hash FROM orders WHERE member = ? AND client_order_id = ?")
                .params(member.name(), clientOrderId)
                .query((rs, n) -> new Object[] {rs.getObject("id", UUID.class), rs.getString("request_hash")})
                .optional()
                .map(row -> {
                    if (!row[1].equals(hash)) {
                        throw new ApiException(ErrorCode.DUPLICATE_ORDER_ID, "A different order already has the id " + clientOrderId + ".");
                    }
                    return new Placed(order((UUID) row[0]), false);
                });
    }

    // ── matching ─────────────────────────────────────────────────────────────

    static boolean crosses(Order o, long last) {
        return o.side() == Side.BUY ? last <= o.limitPaise() : last >= o.limitPaise();
    }

    static boolean beyondProtection(Order o, long last) {
        if (o.protectionPaise() == null) {
            return false;
        }
        return o.side() == Side.BUY ? last > o.protectionPaise() : last < o.protectionPaise();
    }

    /**
     * One pass over the book with a fresh snapshot: fills resting orders the price has reached, and
     * expires everything still open once the market has closed (or a new session has begun).
     * Returns how many orders changed.
     */
    public int match(Snapshot market) {
        List<UUID> open = db.sql("SELECT id FROM orders WHERE status = 'OPEN' ORDER BY created_at").query(UUID.class).list();
        int changed = 0;
        for (UUID id : open) {
            Boolean done = tx.execute(s -> {
                Order o = lockOpen(id);
                if (o == null) {
                    return false;
                }
                if (!market.open() || !o.sessionDate().equals(market.sessionDate())) {
                    finish(o, "EXPIRED", "The market closed before the order executed.");
                    return true;
                }
                Quote q = market.quotes().get(o.symbol());
                if (q != null && o.type() == Type.LIMIT && crosses(o, q.lastPaise())) {
                    fill(o, q.lastPaise());
                    return true;
                }
                return false;
            });
            if (Boolean.TRUE.equals(done)) {
                changed++;
            }
        }
        return changed;
    }

    private void fill(Order o, long price) {
        UUID trade = UUID.randomUUID();
        Instant now = clock.instant();
        db.sql("""
                        INSERT INTO trades (id, order_id, member, client_code, symbol, side, quantity, price_paise, session_date, executed_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(trade, o.id(), o.member(), o.clientCode(), o.symbol(), o.side().name(), o.quantity(), price, o.sessionDate(), ts(now))
                .update();
        db.sql("UPDATE orders SET status = 'FILLED', filled_quantity = quantity, price_paise = ?, trade_id = ?, updated_at = ? WHERE id = ?")
                .params(price, trade, ts(now), o.id()).update();
        tell(o, "ORDER_FILLED", price, trade, null, now);
        log.info("Filled {} {} {} x{} at {}", o.member(), o.side(), o.symbol(), o.quantity(), Money.rupees(price));
    }

    private void finish(Order o, String status, String reason) {
        Instant now = clock.instant();
        db.sql("UPDATE orders SET status = ?, reason = ?, updated_at = ? WHERE id = ?").params(status, reason, ts(now), o.id()).update();
        tell(o, "ORDER_" + status, null, null, reason, now);
    }

    private void tell(Order o, String type, Long price, UUID trade, String reason, Instant at) {
        Member member = props.members().stream().filter(m -> m.name().equals(o.member())).findFirst().orElse(null);
        if (member == null) {
            log.warn("Order {} belongs to {}, no longer a member; nobody to tell", o.id(), o.member());
            return;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("type", type);
        event.put("clientOrderId", o.clientOrderId());
        event.put("exchangeOrderId", o.id().toString());
        event.put("symbol", o.symbol());
        event.put("side", o.side().name());
        event.put("quantity", o.quantity());
        event.put("sessionDate", o.sessionDate().toString());
        if (price != null) {
            event.put("price", Money.rupees(price));
            event.put("tradeId", trade.toString());
        }
        if (reason != null) {
            event.put("reason", reason);
        }
        event.put("occurredAt", at.toString());
        try {
            db.sql("INSERT INTO outbox (id, member, callback_url, body, created_at, next_attempt_at) VALUES (?, ?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID(), member.name(), member.callbackUrl(), json.writeValueAsString(event), ts(at), ts(at))
                    .update();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── the trade tape, for clearing ─────────────────────────────────────────

    /** A session's trades in execution order, from just after {@code after} (a trade id), at most {@code limit}. */
    public List<Trade> trades(LocalDate session, UUID after, int limit) {
        return db.sql("""
                        SELECT id, member, client_code, symbol, side, quantity, price_paise, session_date, executed_at FROM trades
                        WHERE session_date = ?
                          AND (CAST(? AS uuid) IS NULL OR (executed_at, id) > (SELECT executed_at, id FROM trades WHERE id = ?))
                        ORDER BY executed_at, id LIMIT ?""")
                .params(session, after, after, limit)
                .query((rs, n) -> new Trade(rs.getObject("id", UUID.class), rs.getString("member"), rs.getString("client_code"),
                        rs.getString("symbol"), Side.valueOf(rs.getString("side")), rs.getInt("quantity"), rs.getLong("price_paise"),
                        rs.getObject("session_date", LocalDate.class), rs.getTimestamp("executed_at").toInstant()))
                .list();
    }

    // ── cancelling and reading ───────────────────────────────────────────────

    public Order cancel(Member member, String clientOrderId) {
        return tx.execute(s -> {
            Order locked = lock(mine(member, clientOrderId).id());   // waits for a fill in progress, then sees its outcome
            if (!locked.status().equals("OPEN")) {
                if (locked.status().equals("CANCELLED")) {
                    return locked;
                }
                throw new ApiException(ErrorCode.ORDER_NOT_OPEN, "Too late: the order is " + locked.status() + ".");
            }
            finish(locked, "CANCELLED", "Cancelled by the member.");
            return order(locked.id());
        });
    }

    public Order mine(Member member, String clientOrderId) {
        return db.sql(ORDER_SQL + " WHERE member = ? AND client_order_id = ?").params(member.name(), clientOrderId)
                .query(Exchange::row).optional()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No order " + clientOrderId + " from you."));
    }

    private Order order(UUID id) {
        return db.sql(ORDER_SQL + " WHERE id = ?").param(id).query(Exchange::row).single();
    }

    private Order lock(UUID id) {
        return db.sql(ORDER_SQL + " WHERE id = ? FOR UPDATE").param(id).query(Exchange::row).single();
    }

    private Order lockOpen(UUID id) {
        return db.sql(ORDER_SQL + " WHERE id = ? AND status = 'OPEN' FOR UPDATE SKIP LOCKED").param(id).query(Exchange::row)
                .optional().orElse(null);
    }

    private static final String ORDER_SQL = """
            SELECT id, member, client_order_id, client_code, symbol, side, type, quantity, limit_paise, protection_paise, status, filled_quantity,
                   price_paise, trade_id, reason, session_date, created_at, updated_at FROM orders""";

    private static Order row(ResultSet rs, int n) throws SQLException {
        return new Order(rs.getObject("id", UUID.class), rs.getString("member"), rs.getString("client_order_id"),
                rs.getString("client_code"), rs.getString("symbol"), Side.valueOf(rs.getString("side")), Type.valueOf(rs.getString("type")),
                rs.getInt("quantity"), rs.getObject("limit_paise", Long.class), rs.getObject("protection_paise", Long.class),
                rs.getString("status"), rs.getInt("filled_quantity"), rs.getObject("price_paise", Long.class),
                rs.getObject("trade_id", UUID.class), rs.getString("reason"), rs.getObject("session_date", LocalDate.class),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    static String hash(NewOrder o) {
        String canonical = String.join("|", o.clientCode(), o.symbol(), o.side().name(), o.type().name(), String.valueOf(o.quantity()),
                String.valueOf(o.limitPaise()), String.valueOf(o.protectionPaise()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }
}
