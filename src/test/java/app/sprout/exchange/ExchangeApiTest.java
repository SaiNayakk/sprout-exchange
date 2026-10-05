package app.sprout.exchange;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.sprout.contracts.Contracts;
import app.sprout.exchange.domain.Callbacks;
import app.sprout.exchange.domain.Exchange;
import app.sprout.exchange.domain.Prices;
import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The exchange on a real Postgres, against a stand-in market-data service whose prices and hours the
 * tests set, and a stand-in member that records (and can refuse) its callbacks.
 */
@Testcontainers
@SpringBootTest(properties = {"spring.config.name=exchange", "sprout.exchange.price-poll=1h", "sprout.exchange.delivery-check=1h"})
@AutoConfigureMockMvc
class ExchangeApiTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    static final ObjectMapper JSON = new ObjectMapper();
    static final String KEY = "dev-only-member-key";
    static final String SECRET = "dev-only-exchange-webhook-secret";

    // the stand-ins' state
    static final Map<String, String> LAST = new ConcurrentHashMap<>();
    static final AtomicReference<String> STATE = new AtomicReference<>("OPEN");
    static final AtomicReference<String> SESSION = new AtomicReference<>("2026-10-05");
    static final AtomicBoolean MEMBER_DOWN = new AtomicBoolean();
    static final List<JsonNode> CALLBACKS = new CopyOnWriteArrayList<>();
    static final HttpServer STANDINS = standIns();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String base = "http://127.0.0.1:" + STANDINS.getAddress().getPort();
        r.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=exchange");
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("sprout.exchange.marketdata-url", () -> base);
        // a list from here replaces the configured one whole, so the member is given in full
        r.add("sprout.exchange.members[0].name", () -> "sprout");
        r.add("sprout.exchange.members[0].key", () -> KEY);
        r.add("sprout.exchange.members[0].webhook-secret", () -> SECRET);
        r.add("sprout.exchange.members[0].callback-url", () -> base + "/callbacks");
    }

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.parse("2026-10-05T05:00:00Z"));
        }
    }

    static final OpenApiInteractionValidator CONTRACT = OpenApiInteractionValidator
            .createForInlineApiSpecification(Contracts.read(Contracts.EXCHANGE_V1))
            .withBasePathOverride("/")
            .withLevelResolver(LevelResolver.create().withLevel("validation.request", ValidationReport.Level.IGNORE).build())
            .build();
    static final ResultMatcher MATCHES_CONTRACT = openApi().isValid(CONTRACT);

    @Autowired MockMvc mvc;
    @Autowired MutableClock clock;
    @Autowired Prices prices;
    @Autowired Exchange exchange;
    @Autowired Callbacks callbacks;
    @Autowired JdbcClient db;

    @BeforeEach
    void freshMarket() {
        LAST.put("HARBOR", "1000.00");
        LAST.put("INKWELL", "200.00");
        STATE.set("OPEN");
        SESSION.set("2026-10-05");
        MEMBER_DOWN.set(false);
        clock.advance(Duration.ofMinutes(5));   // anything left over from other tests is due now
        tick();
        callbacks.deliverDue();
        CALLBACKS.clear();
    }

    /** One round of the exchange's loop: fresh prices, then matching. */
    void tick() {
        prices.refresh().ifPresent(exchange::match);
    }

    ResultActions place(Map<String, Object> order) throws Exception {
        return mvc.perform(post("/member/v1/orders").header("X-Member-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(order)));
    }

    static Map<String, Object> order(String side, String type, int qty, String... prices) {
        Map<String, Object> o = new java.util.LinkedHashMap<>();
        o.put("clientOrderId", UUID.randomUUID().toString());
        o.put("symbol", "HARBOR");
        o.put("side", side);
        o.put("type", type);
        o.put("quantity", qty);
        if (prices.length > 0 && prices[0] != null) {
            o.put(type.equals("LIMIT") ? "limitPrice" : "protectionPrice", prices[0]);
        }
        return o;
    }

    JsonNode body(ResultActions r) throws Exception {
        return JSON.readTree(r.andReturn().getResponse().getContentAsString());
    }

    List<JsonNode> delivered() {
        callbacks.deliverDue();
        return new ArrayList<>(CALLBACKS);
    }

    // ── executing ────────────────────────────────────────────────────────────

    @Test
    void aMarketOrderFillsAtOnceAtTheLastPriceAndTheMemberIsToldSigned() throws Exception {
        JsonNode o = body(place(order("BUY", "MARKET", 10, "1030.00")).andExpect(status().isCreated()).andExpect(MATCHES_CONTRACT)
                .andExpect(jsonPath("$.status").value("FILLED")).andExpect(jsonPath("$.price").value("1000.00"))
                .andExpect(jsonPath("$.filledQuantity").value(10)));
        List<JsonNode> events = delivered();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).path("type").asText()).isEqualTo("ORDER_FILLED");
        assertThat(events.get(0).path("clientOrderId").asText()).isEqualTo(o.path("clientOrderId").asText());
        assertThat(events.get(0).path("tradeId").asText()).isEqualTo(o.path("tradeId").asText());
        assertThat(db.sql("SELECT COUNT(*) FROM trades WHERE order_id = ?").param(UUID.fromString(o.path("exchangeOrderId").asText()))
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void aLimitOrderRestsUntilThePriceReachesItThenFillsAtThatPrice() throws Exception {
        JsonNode buy = body(place(order("BUY", "LIMIT", 5, "990.00")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN")));
        JsonNode sell = body(place(order("SELL", "LIMIT", 5, "1010.00")).andExpect(jsonPath("$.status").value("OPEN")));
        LAST.put("HARBOR", "995.00");
        tick();
        assertThat(state(buy)).isEqualTo("OPEN");
        LAST.put("HARBOR", "989.50");
        tick();
        mvc.perform(get("/member/v1/orders/" + buy.path("clientOrderId").asText()).header("X-Member-Key", KEY))
                .andExpect(status().isOk()).andExpect(MATCHES_CONTRACT)
                .andExpect(jsonPath("$.status").value("FILLED")).andExpect(jsonPath("$.price").value("989.50"));
        assertThat(state(sell)).isEqualTo("OPEN");
        LAST.put("HARBOR", "1012.00");
        tick();
        assertThat(state(sell)).isEqualTo("FILLED");
        assertThat(delivered()).extracting(e -> e.path("type").asText()).containsExactly("ORDER_FILLED", "ORDER_FILLED");
    }

    @Test
    void aMarketOrderBeyondItsProtectionIsCancelledNotFilled() throws Exception {
        place(order("BUY", "MARKET", 1, "980.00")).andExpect(status().isCreated()).andExpect(MATCHES_CONTRACT)
                .andExpect(jsonPath("$.status").value("CANCELLED")).andExpect(jsonPath("$.reason").exists());
        assertThat(delivered()).extracting(e -> e.path("type").asText()).containsExactly("ORDER_CANCELLED");
    }

    String state(JsonNode o) throws Exception {
        return body(mvc.perform(get("/member/v1/orders/" + o.path("clientOrderId").asText()).header("X-Member-Key", KEY)))
                .path("status").asText();
    }

    // ── refusing ─────────────────────────────────────────────────────────────

    @Test
    void sendingTheSameOrderTwiceIsOneOrderAndADifferentOneUnderItsIdIsRefused() throws Exception {
        Map<String, Object> o = order("BUY", "MARKET", 3);
        String first = body(place(o).andExpect(status().isCreated())).path("exchangeOrderId").asText();
        place(o).andExpect(status().isOk()).andExpect(MATCHES_CONTRACT).andExpect(jsonPath("$.exchangeOrderId").value(first));
        Map<String, Object> other = new java.util.LinkedHashMap<>(o);
        other.put("quantity", 4);
        place(other).andExpect(status().isConflict()).andExpect(MATCHES_CONTRACT).andExpect(jsonPath("$.code").value("DUPLICATE_ORDER_ID"));
        assertThat(delivered()).hasSize(1);
    }

    @Test
    void ordersOutsideTheRulesAreRefusedAndNothingIsRecorded() throws Exception {
        int before = db.sql("SELECT COUNT(*) FROM orders").query(Integer.class).single();
        place(order("BUY", "LIMIT", 1, "1300.00")).andExpect(status().isUnprocessableEntity()).andExpect(MATCHES_CONTRACT)
                .andExpect(jsonPath("$.code").value("PRICE_OUT_OF_BAND"));
        place(order("BUY", "LIMIT", 1, "1000.03")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_TICK"));
        Map<String, Object> index = order("BUY", "MARKET", 1);
        index.put("symbol", "SPROUT20");
        place(index).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("UNKNOWN_INSTRUMENT"));
        place(order("BUY", "LIMIT", 0, "1000.00")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/member/v1/orders").header("X-Member-Key", "not-a-member").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(order("BUY", "MARKET", 1))))
                .andExpect(status().isUnauthorized()).andExpect(MATCHES_CONTRACT).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        STATE.set("CLOSED");
        tick();
        place(order("BUY", "MARKET", 1)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("MARKET_CLOSED"));
        assertThat(db.sql("SELECT COUNT(*) FROM orders").query(Integer.class).single()).isEqualTo(before);
    }

    @Test
    void withoutFreshPricesTheExchangeRefusesRatherThanGuess() throws Exception {
        clock.advance(Duration.ofSeconds(30));   // the last snapshot is now stale
        place(order("BUY", "MARKET", 1)).andExpect(status().isServiceUnavailable()).andExpect(MATCHES_CONTRACT)
                .andExpect(jsonPath("$.code").value("UPSTREAM_UNAVAILABLE"));
    }

    // ── cancelling and expiring ──────────────────────────────────────────────

    @Test
    void anOpenOrderCanBeCancelledOnceAndAFilledOneCannot() throws Exception {
        JsonNode open = body(place(order("BUY", "LIMIT", 2, "950.00")));
        String id = open.path("clientOrderId").asText();
        mvc.perform(delete("/member/v1/orders/" + id).header("X-Member-Key", KEY)).andExpect(status().isOk()).andExpect(MATCHES_CONTRACT)
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(delete("/member/v1/orders/" + id).header("X-Member-Key", KEY)).andExpect(status().isOk());
        JsonNode filled = body(place(order("BUY", "MARKET", 2)));
        mvc.perform(delete("/member/v1/orders/" + filled.path("clientOrderId").asText()).header("X-Member-Key", KEY))
                .andExpect(status().isConflict()).andExpect(MATCHES_CONTRACT).andExpect(jsonPath("$.code").value("ORDER_NOT_OPEN"));
        mvc.perform(get("/member/v1/orders/nope").header("X-Member-Key", KEY)).andExpect(status().isNotFound()).andExpect(MATCHES_CONTRACT);
        assertThat(delivered()).extracting(e -> e.path("type").asText()).containsExactly("ORDER_CANCELLED", "ORDER_FILLED");
    }

    @Test
    void whatIsStillOpenAtTheCloseExpires() throws Exception {
        JsonNode o = body(place(order("SELL", "LIMIT", 7, "1100.00")));
        STATE.set("CLOSED");
        tick();
        assertThat(state(o)).isEqualTo("EXPIRED");
        assertThat(delivered()).extracting(e -> e.path("type").asText()).containsExactly("ORDER_EXPIRED");
    }

    @Test
    void racingCancelsAndAFillChangeAnOrderExactlyOnce() throws Exception {
        JsonNode o = body(place(order("BUY", "LIMIT", 1, "999.00")));
        String id = o.path("clientOrderId").asText();
        LAST.put("HARBOR", "998.00");
        prices.refresh();
        List<Thread> racers = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            racers.add(Thread.ofVirtual().start(() -> {
                try {
                    mvc.perform(delete("/member/v1/orders/" + id).header("X-Member-Key", KEY));
                } catch (Exception ignored) {
                    // a refused cancel is fine here
                }
            }));
        }
        racers.add(Thread.ofVirtual().start(this::tick));
        for (Thread t : racers) {
            t.join();
        }
        assertThat(delivered()).hasSize(1);
    }

    // ── telling the member ───────────────────────────────────────────────────

    @Test
    void aMemberThatIsDownGetsEveryEventWhenItComesBack() throws Exception {
        MEMBER_DOWN.set(true);
        place(order("BUY", "MARKET", 1));
        place(order("SELL", "MARKET", 1));
        assertThat(delivered()).isEmpty();
        MEMBER_DOWN.set(false);
        assertThat(delivered()).isEmpty();          // not due again yet: backing off
        clock.advance(Duration.ofSeconds(3));
        assertThat(delivered()).hasSize(2);
        clock.advance(Duration.ofMinutes(5));
        assertThat(delivered()).hasSize(2);         // each delivered once
    }

    // ── the stand-ins ────────────────────────────────────────────────────────

    static HttpServer standIns() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/v1/instruments", ex -> reply(ex, 200, Map.of("instruments", List.of(
                    Map.of("symbol", "HARBOR", "tickSize", 0.05, "tradable", true),
                    Map.of("symbol", "INKWELL", "tickSize", 0.05, "tradable", true),
                    Map.of("symbol", "SPROUT20", "tickSize", 0.01, "tradable", false)))));
            s.createContext("/v1/quotes", ex -> {
                List<Map<String, Object>> quotes = new ArrayList<>();
                for (String sym : ex.getRequestURI().getQuery().replace("symbols=", "").split(",")) {
                    quotes.add(Map.of("symbol", sym, "last", Double.parseDouble(LAST.get(sym)),
                            "prevClose", sym.equals("HARBOR") ? 1000.0 : 200.0));
                }
                reply(ex, 200, Map.of("market", market(), "quotes", quotes));
            });
            s.createContext("/v1/market", ex -> reply(ex, 200, market()));
            s.createContext("/callbacks", ex -> {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (MEMBER_DOWN.get()) {
                    reply(ex, 503, Map.of());
                    return;
                }
                String sig = ex.getRequestHeaders().getFirst("X-Exchange-Signature");
                if (!("sha256=" + Callbacks.sign(SECRET, body)).equals(sig)) {
                    reply(ex, 401, Map.of());
                    return;
                }
                CALLBACKS.add(JSON.readTree(body));
                reply(ex, 204, null);
            });
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static Map<String, Object> market() {
        return Map.of("state", STATE.get(), "sessionDate", SESSION.get());
    }

    static void reply(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            ex.getResponseBody().write(bytes);
        }
        ex.close();
    }
}
