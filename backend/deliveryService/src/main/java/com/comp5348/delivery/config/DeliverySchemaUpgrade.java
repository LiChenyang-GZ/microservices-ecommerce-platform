package com.comp5348.delivery.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Brings an existing delivery table up to what wave 2 step 4b needs (Christy's choice on question 3: an
 * idempotent SQL step at startup, no Flyway). Same idea as store's OrderStatusCheckUpgrade.
 *
 *   1. order_id unique: one order, one delivery row. OrderPaid and cancel-by-order both use
 *      INSERT ... ON CONFLICT (order_id), which Postgres refuses without a unique index.
 *   2. the customer and address columns may be null: a cancel marker (cancel before OrderPaid) has none of them.
 *
 * ddl-auto=update does neither on a table it created earlier: it never drops NOT NULL, and a failed
 * "add unique" only logs a line. A fresh test database hides this; the compose volume does not (B11).
 *
 * If the unique index cannot be built (two rows for one order already exist), startup fails on purpose:
 * running without it would turn de-duplication off without anyone noticing.
 *
 * Runs after Hibernate has created or updated the tables and before the Kafka listeners start.
 */
@Component
@DependsOn("entityManagerFactory")
public class DeliverySchemaUpgrade {

    private static final Logger logger = LoggerFactory.getLogger(DeliverySchemaUpgrade.class);

    private static final String[] NULLABLE = {"user_name", "to_address", "from_address", "product_name", "email"};

    private final JdbcTemplate jdbc;

    public DeliverySchemaUpgrade(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void upgrade() {
        for (String column : NULLABLE) {
            jdbc.execute("ALTER TABLE delivery ALTER COLUMN " + column + " DROP NOT NULL");
        }
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_delivery_order_id ON delivery (order_id)");
        logger.info("delivery table: order_id unique, {} nullable", String.join(", ", NULLABLE));
    }
}
