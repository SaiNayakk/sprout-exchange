package app.sprout.exchange.domain;

import app.sprout.exchange.config.ExchangeProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The exchange's view of the market, read from the market-data service: whether it is open, the
 * session, and each instrument's last and previous closing price. Refreshed by polling; a reader
 * always sees one consistent snapshot.
 *
 * <p>Polling, not the NATS tick stream: the exchange needs the latest price, not every tick, and
 * this way it keeps working (or knows it can't) whatever happens to NATS.
 */
@Component
public class Prices {

    private static final Logger log = LoggerFactory.getLogger(Prices.class);

    public record Instrument(String symbol, long tickPaise, boolean tradable) {}

    public record Quote(long lastPaise, long prevClosePaise) {}

    public record Snapshot(boolean open, LocalDate sessionDate, Map<String, Quote> quotes, Instant fetchedAt) {}

    private final ExchangeProperties props;
    private final ObjectMapper json;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private volatile Map<String, Instrument> instruments = Map.of();
    private volatile Snapshot snapshot;

    public Prices(ExchangeProperties props, ObjectMapper json, Clock clock) {
        this.props = props;
        this.json = json;
        this.clock = clock;
    }

    /** Fetches a fresh snapshot. Returns it, or empty if market data couldn't be read. */
    public Optional<Snapshot> refresh() {
        try {
            if (instruments.isEmpty()) {
                Map<String, Instrument> found = new HashMap<>();
                for (JsonNode i : get("/v1/instruments").path("instruments")) {
                    long tick = Math.round(i.path("tickSize").asDouble() * 100);
                    found.put(i.path("symbol").asText(), new Instrument(i.path("symbol").asText(), Math.max(1, tick),
                            i.path("tradable").asBoolean()));
                }
                instruments = Map.copyOf(found);
            }
            List<String> symbols = instruments.values().stream().filter(Instrument::tradable).map(Instrument::symbol).sorted().toList();
            Map<String, Quote> quotes = new HashMap<>();
            JsonNode market = null;
            for (int from = 0; from < symbols.size(); from += 25) {
                JsonNode page = get("/v1/quotes?symbols=" + String.join(",", symbols.subList(from, Math.min(symbols.size(), from + 25))));
                market = page.path("market");
                for (JsonNode q : page.path("quotes")) {
                    quotes.put(q.path("symbol").asText(), new Quote(Math.round(q.path("last").asDouble() * 100),
                            Math.round(q.path("prevClose").asDouble() * 100)));
                }
            }
            if (market == null) {
                market = get("/v1/market");
            }
            Snapshot s = new Snapshot("OPEN".equals(market.path("state").asText()),
                    LocalDate.parse(market.path("sessionDate").asText()), Map.copyOf(quotes), clock.instant());
            snapshot = s;
            return Optional.of(s);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.debug("Couldn't read market data: {}", e.toString());
            return Optional.empty();
        }
    }

    /** The latest snapshot, if it is recent enough to trade on. */
    public Optional<Snapshot> current() {
        Snapshot s = snapshot;
        if (s == null || s.fetchedAt().plus(props.staleAfter()).isBefore(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(s);
    }

    public Optional<Instrument> instrument(String symbol) {
        return Optional.ofNullable(instruments.get(symbol));
    }

    private JsonNode get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(props.marketdataUrl() + path)).timeout(Duration.ofSeconds(3)).GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IllegalStateException("market data answered " + res.statusCode() + " for " + path);
        }
        return json.readTree(res.body());
    }
}
