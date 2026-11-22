package comp5348.storeservice.config;

import comp5348.storeservice.model.Account;
import comp5348.storeservice.model.Role;
import comp5348.storeservice.repository.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * The first admin (auth PR 3, scenario E6). There is no sign-up for admins: at startup store creates one from
 * ADMIN_EMAIL / ADMIN_PASSWORD (store.admin.email / store.admin.password), if both are set.
 *
 * - Not configured → no admin, a WARN, store still starts (the shop works; the admin dashboard and refunds do not).
 * - An account with that email already exists and is not an admin → it is NOT promoted, WARN. Until activation checks
 *   the verification code (A11), anyone can register that email first and activate it; promoting it would hand them
 *   the admin role. Pick another ADMIN_EMAIL, or promote by hand in the database after checking who owns it.
 * - The password is stored as BCrypt, like every account. Changing ADMIN_PASSWORD later does not change an existing
 *   admin's password: the variable only seeds the first one.
 */
@Component
public class AdminBootstrap implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AccountRepository accounts;
    private final String email;
    private final String password;

    public AdminBootstrap(AccountRepository accounts,
                          @Value("${store.admin.email:}") String email,
                          @Value("${store.admin.password:}") String password) {
        this.accounts = accounts;
        this.email = email == null ? "" : email.trim();
        this.password = password == null ? "" : password;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (email.isEmpty() || password.isBlank()) {
            logger.warn("No admin configured (ADMIN_EMAIL / ADMIN_PASSWORD empty): nobody can use the admin dashboard or refunds");
            return;
        }
        Optional<Account> existing = accounts.findByEmail(email);
        if (existing.isPresent()) {
            if (existing.get().isAdmin()) {
                logger.info("Admin account already exists");
            } else {
                logger.warn("ADMIN_EMAIL belongs to an existing ordinary account; it was NOT made an admin. "
                        + "Use another ADMIN_EMAIL, or promote it by hand after checking who registered it");
            }
            return;
        }
        Account admin = new Account();
        admin.setEmail(email);
        admin.setUsername(email); // usernames are unique; the email is the one value guaranteed free here
        admin.setPassword(new BCryptPasswordEncoder().encode(password));
        admin.setEmailVerified(true);
        admin.setActive(true);
        admin.setRole(Role.ADMIN);
        accounts.save(admin);
        logger.info("Created the admin account from ADMIN_EMAIL");
    }
}
