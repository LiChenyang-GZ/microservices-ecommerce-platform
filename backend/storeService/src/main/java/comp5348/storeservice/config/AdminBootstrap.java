package comp5348.storeservice.config;

import comp5348.storeservice.model.Account;
import comp5348.storeservice.model.Role;
import comp5348.storeservice.repository.AccountRepository;
import comp5348.storeservice.utils.EmailAddress;
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
 * - An account with that email already exists and is not an admin → it is NOT promoted, WARN. Anyone can register an
 *   address they do not own (since A11 they cannot activate it without the emailed code, but the account row stays);
 *   promoting it would hand them the admin role. Pick another ADMIN_EMAIL, or promote by hand in the database after checking who owns it.
 * - The password is stored as BCrypt, like every account. Changing ADMIN_PASSWORD later does not change an existing
 *   admin's password: the variable only seeds the first one.
 * - Promoting or demoting someone by hand in the database takes effect at their next log-in: the role travels in
 *   the JWT, which stays valid for up to 24 hours (see Role).
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
        // One spelling per mailbox (Obsidian 74): "Admin@Shop.com" must meet an existing admin@shop.com, or a second
        // account for the same inbox would be made admin
        this.email = email == null ? "" : EmailAddress.normalize(email);
        this.password = password == null ? "" : password;
    }

    /**
     * The email as username if nobody has it, otherwise admin-xxxxxxxx. Sign-up no longer takes a username with an "@"
     * (Obsidian 78), so only an older row can hold this email as its username; a clash on the unique column would stop
     * store from starting (review 47 🟠2).
     * Admins log in with the email anyway: login looks the identifier up by email first.
     */
    private String freeUsername() {
        if (accounts.findByUsername(email).isEmpty()) {
            return email;
        }
        String candidate;
        do {
            candidate = "admin-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        } while (accounts.findByUsername(candidate).isPresent());
        logger.warn("An ordinary account already uses ADMIN_EMAIL as its username; the admin gets username {}", candidate);
        return candidate;
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
        admin.setUsername(freeUsername());
        admin.setPassword(new BCryptPasswordEncoder().encode(password));
        admin.setEmailVerified(true);
        admin.setActive(true);
        admin.setRole(Role.ADMIN);
        accounts.save(admin);
        logger.info("Created the admin account from ADMIN_EMAIL");
    }
}
