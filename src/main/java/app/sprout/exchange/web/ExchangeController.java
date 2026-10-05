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
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** The member API (exchange-v1.yaml). */
@RestController
public class ExchangeController {

    public record NewOrderRequest(String clientOrderId, String symbol, Side side, Type type, Integer quantity, String limitPrice,
                                  String protectionPrice) {}

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
        Placed p = exchange.place(member, new NewOrder(req.clientOrderId(), req.symbol(), req.side(), req.type(), req.quantity(),
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
