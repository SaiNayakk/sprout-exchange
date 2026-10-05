package app.sprout.exchange.web;

import app.sprout.exchange.config.ExchangeProperties;
import app.sprout.exchange.config.ExchangeProperties.Member;
import app.sprout.exchange.domain.ApiException;
import app.sprout.exchange.domain.ErrorCode;
import app.sprout.exchange.domain.Exchange;
import app.sprout.exchange.domain.Exchange.NewOrder;
import app.sprout.exchange.domain.Exchange.Order;
import app.sprout.exchange.domain.Exchange.Placed;
import app.sprout.exchange.domain.Exchange.Side;
import app.sprout.exchange.domain.Exchange.Type;
import app.sprout.exchange.domain.Money;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The member API (exchange-v1.yaml). */
@RestController
public class ExchangeController {

    public record NewOrderRequest(String clientOrderId, String clientCode, String symbol, Side side, Type type, Integer quantity,
                                  String limitPrice, String protectionPrice) {}

    private final Exchange exchange;
    private final ExchangeProperties props;

    public ExchangeController(Exchange exchange, ExchangeProperties props) {
        this.exchange = exchange;
        this.props = props;
    }

    @PostMapping("/member/v1/orders")
    public ResponseEntity<Map<String, Object>> place(@RequestHeader(value = "X-Member-Key", required = false) String key,
                                                     @RequestBody NewOrderRequest req) {
        Member member = member(key);
        if (req.quantity() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "quantity is required.");
        }
        // no client code: the member's own (proprietary) trade
        String clientCode = req.clientCode() == null ? "PRO" : req.clientCode();
        Placed p = exchange.place(member, new NewOrder(req.clientOrderId(), clientCode, req.symbol(), req.side(), req.type(), req.quantity(),
                req.limitPrice() == null ? null : Money.paise(req.limitPrice()),
                req.protectionPrice() == null ? null : Money.paise(req.protectionPrice())));
        return ResponseEntity.status(p.created() ? HttpStatus.CREATED : HttpStatus.OK).body(dto(p.order()));
    }

    @GetMapping("/member/v1/orders/{clientOrderId}")
    public Map<String, Object> get(@RequestHeader(value = "X-Member-Key", required = false) String key,
                                   @PathVariable String clientOrderId) {
        return dto(exchange.mine(member(key), clientOrderId));
    }

    @DeleteMapping("/member/v1/orders/{clientOrderId}")
    public Map<String, Object> cancel(@RequestHeader(value = "X-Member-Key", required = false) String key,
                                      @PathVariable String clientOrderId) {
        return dto(exchange.cancel(member(key), clientOrderId));
    }

    /** A member's own trades in a session, a page at a time, to reconcile its books with the exchange. */
    @GetMapping("/member/v1/trades")
    public Map<String, Object> memberTrades(@RequestHeader(value = "X-Member-Key", required = false) String key,
                                            @RequestParam LocalDate sessionDate, @RequestParam(required = false) UUID after,
                                            @RequestParam(required = false) Integer limit) {
        Member member = member(key);
        int n = limit == null ? 500 : limit;
        if (n < 1 || n > 1000) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "limit is between 1 and 1000.");
        }
        return Map.of("trades", exchange.memberTrades(member, sessionDate, after, n).stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tradeId", t.id().toString());
            m.put("clientOrderId", t.clientOrderId());
            m.put("clientCode", t.clientCode());
            m.put("symbol", t.symbol());
            m.put("side", t.side().name());
            m.put("quantity", t.quantity());
            m.put("price", Money.rupees(t.pricePaise()));
            m.put("sessionDate", t.sessionDate().toString());
            m.put("executedAt", t.executedAt().toString());
            return m;
        }).toList());
    }

    /** The day's trades for the clearing corporation, a page at a time (pass the last trade id as {@code after}). */
    @GetMapping("/clearing/v1/trades")
    public Map<String, Object> trades(@RequestHeader(value = "X-Clearing-Key", required = false) String key,
                                      @RequestParam LocalDate sessionDate, @RequestParam(required = false) UUID after,
                                      @RequestParam(required = false) Integer limit) {
        if (key == null || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), props.clearingKey().getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Only the clearing corporation reads the trade tape.");
        }
        int n = limit == null ? 500 : limit;
        if (n < 1 || n > 1000) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "limit is between 1 and 1000.");
        }
        return Map.of("trades", exchange.trades(sessionDate, after, n).stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tradeId", t.id().toString());
            m.put("member", t.member());
            m.put("clientCode", t.clientCode());
            m.put("symbol", t.symbol());
            m.put("side", t.side().name());
            m.put("quantity", t.quantity());
            m.put("price", Money.rupees(t.pricePaise()));
            m.put("sessionDate", t.sessionDate().toString());
            m.put("executedAt", t.executedAt().toString());
            return m;
        }).toList());
    }

    private Member member(String key) {
        if (key != null) {
            byte[] given = key.getBytes(StandardCharsets.UTF_8);
            for (Member m : props.members()) {
                if (MessageDigest.isEqual(given, m.key().getBytes(StandardCharsets.UTF_8))) {
                    return m;
                }
            }
        }
        throw new ApiException(ErrorCode.UNAUTHENTICATED, "Send a valid X-Member-Key.");
    }

    static Map<String, Object> dto(Order o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("exchangeOrderId", o.id().toString());
        m.put("clientOrderId", o.clientOrderId());
        m.put("clientCode", o.clientCode());
        m.put("symbol", o.symbol());
        m.put("side", o.side().name());
        m.put("type", o.type().name());
        m.put("quantity", o.quantity());
        if (o.limitPaise() != null) {
            m.put("limitPrice", Money.rupees(o.limitPaise()));
        }
        if (o.protectionPaise() != null) {
            m.put("protectionPrice", Money.rupees(o.protectionPaise()));
        }
        m.put("status", o.status());
        m.put("filledQuantity", o.filledQuantity());
        if (o.pricePaise() != null) {
            m.put("price", Money.rupees(o.pricePaise()));
            m.put("tradeId", o.tradeId().toString());
        }
        if (o.reason() != null) {
            m.put("reason", o.reason());
        }
        m.put("sessionDate", o.sessionDate().toString());
        m.put("createdAt", o.createdAt().toString());
        m.put("updatedAt", o.updatedAt().toString());
        return m;
    }
}
