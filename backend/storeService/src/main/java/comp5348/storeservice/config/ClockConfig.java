package comp5348.storeservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The time source for payment retries and the unpaid-order timeout.
 * Code asks this bean for "now" instead of calling LocalDateTime.now(), so tests can move time forward.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
