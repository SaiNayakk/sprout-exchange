package app.sprout.exchange;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The Sprout Stock Exchange (SSX): a simulated exchange that executes members' orders against the simulated market.
 *
 * <p>Runs on its own ({@link #main}) or inside a shared JVM host, which calls {@link #builder()}.
 * Either way it reads {@code exchange.yml}, never {@code application.yml}, so services sharing a
 * host can't read each other's settings.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ExchangeApplication {

    public static final String CONFIG_NAME = "exchange";

    public static void main(String[] args) {
        builder().run(args);
    }

    public static SpringApplicationBuilder builder() {
        return new SpringApplicationBuilder(ExchangeApplication.class)
                .properties("spring.config.name=" + CONFIG_NAME);
    }
}
