package comp5348.storeservice.config;

import comp5348.storeservice.model.OrderStatus;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Keeps the CHECK constraint on orders.status in step with the OrderStatus enum (Christy's choice on question 3:
 * an idempotent SQL step at startup, no Flyway).
 *
 * Why: Hibernate 6 writes CHECK (status IN (...)) when it first creates the table. ddl-auto=update never changes
 * it afterwards, so a database created before LOST / REFUNDED existed rejects them. A brand-new test database
 * does not show the problem; an existing one (like the compose volume) does.
 *
 * Runs after Hibernate has created or updated the tables (@DependsOn entityManagerFactory) and before the
 * Kafka listeners start (they start once every bean is ready), so no event is handled against the old constraint.
 */
@Component
@DependsOn("entityManagerFactory")
public class OrderStatusCheckUpgrade {

    private static final Logger logger = LoggerFactory.getLogger(OrderStatusCheckUpgrade.class);

    private final JdbcTemplate jdbc;

    public OrderStatusCheckUpgrade(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void upgrade() {
        String allowed = Arrays.stream(OrderStatus.values())
                .map(s -> "'" + s.name() + "'")
                .collect(Collectors.joining(", "));
        // Two statements in one call run in one implicit transaction: there is never a moment without the check
        jdbc.execute("ALTER TABLE IF EXISTS orders DROP CONSTRAINT IF EXISTS orders_status_check; "
                + "ALTER TABLE IF EXISTS orders ADD CONSTRAINT orders_status_check CHECK (status IN (" + allowed + "))");
        logger.info("orders_status_check now allows {}", allowed);
    }
}
