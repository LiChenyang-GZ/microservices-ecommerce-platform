package comp5348.storeservice.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Gives accounts created before roles existed the USER role (auth PR 3, scenario E7). Same approach as
 * OrderStatusCheckUpgrade (ADR-21): an idempotent SQL step at startup, no Flyway.
 *
 * ddl-auto=update adds account.role as a nullable column, so old rows are NULL. Account#getRole already reads NULL
 * as USER; this makes the table say so too, and gives the column a default for rows inserted outside JPA.
 * Runs after Hibernate has updated the tables (@DependsOn entityManagerFactory).
 */
@Component
@DependsOn("entityManagerFactory")
public class AccountRoleUpgrade {

    private static final Logger logger = LoggerFactory.getLogger(AccountRoleUpgrade.class);

    private final JdbcTemplate jdbc;

    public AccountRoleUpgrade(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void upgrade() {
        jdbc.execute("ALTER TABLE IF EXISTS account ALTER COLUMN role SET DEFAULT 'USER'");
        int filled = jdbc.update("UPDATE account SET role = 'USER' WHERE role IS NULL");
        if (filled > 0) {
            logger.info("Gave {} account(s) without a role the USER role", filled);
        }
    }
}
