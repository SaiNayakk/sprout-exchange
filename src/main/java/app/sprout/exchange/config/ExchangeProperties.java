package app.sprout.exchange.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings under {@code sprout.exchange} in exchange.yml. */
@ConfigurationProperties("sprout.exchange")
public record ExchangeProperties(
        String marketdataUrl,
        Duration pricePoll,
        Duration staleAfter,
        int bandPercent,
        Duration deliveryCheck,
        List<Member> members,
        String clearingKey) {

    /** A broker allowed to trade: how it signs in, and where and how its callbacks go. */
    public record Member(String name, String key, String callbackUrl, String webhookSecret) {}
}
