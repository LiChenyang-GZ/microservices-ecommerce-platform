package comp5348.storeservice.service;


import comp5348.storeservice.config.ServiceTokens;
import comp5348.storeservice.model.Account;
import comp5348.storeservice.repository.AccountRepository;
import comp5348.storeservice.utils.EmailAddress;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import comp5348.storeservice.dto.AccountDTO;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import java.beans.Transient;
import java.math.BigDecimal;
import java.util.Optional;

@Service
public class AccountService {

    private static final Logger logger = LoggerFactory.getLogger(AccountService.class);
    private static final int EMAIL_TIMEOUT_MS = 5_000;

    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    /** Only talks to email (codes: send, check, reset), so it carries email's own service token on every call */
    private final RestTemplate emailRestTemplate;
    @Value("${email.service.url:http://localhost:8083}")
    private String emailServiceUrl;

    @Autowired
    public AccountService(AccountRepository accountRepository,
                          RestTemplateBuilder restTemplateBuilder,
                          @Value("${email.service-token:}") String emailServiceToken) {
        this.accountRepository = accountRepository;
        ServiceTokens.require("email.service-token", "EMAIL_SERVICE_TOKEN", "email", emailServiceToken);
        // SimpleClientHttpRequestFactory: plain HTTP/1.1, same as the old `new RestTemplate()` and the adapters.
        // The builder's default (JDK HttpClient) first tries an HTTP/2 upgrade, which some servers reset.
        // 5-second connect / read timeouts, as in the adapters (scenario E1b): without them a hanging email holds
        // one Tomcat thread per password-reset request until email answers.
        this.emailRestTemplate = restTemplateBuilder
                .requestFactory(() -> {
                    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                    factory.setConnectTimeout(EMAIL_TIMEOUT_MS);
                    factory.setReadTimeout(EMAIL_TIMEOUT_MS);
                    return factory;
                })
                .defaultHeader(ServiceTokens.HEADER, emailServiceToken)
                .build();
    }

    @Autowired
    private comp5348.storeservice.adapter.BankAdapter bankAdapter;

    /** letters, digits and . _ % + - before the @; letters, digits, . and - after it; a TLD of 2+ letters */
    private static final java.util.regex.Pattern EMAIL_FORMAT =
            java.util.regex.Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    /**
     * 3-32 ASCII letters, digits, ".", "_" or "-" (Obsidian 78). No "@": log-in looks an identifier up by email first,
     * so a username shaped like someone's email reached that account (review 75 🟡1), and could pose as it wherever
     * the username is shown (the delivery's customer name).
     * No spaces, look-alike letters (Cyrillic "а" in "аdmin") or control characters, for the same reason. Sign-up only:
     * older accounts keep their usernames (locally only the bootstrapped admins have an "@": their own email).
     */
    private static final java.util.regex.Pattern USERNAME_FORMAT = java.util.regex.Pattern.compile("^[A-Za-z0-9._-]{3,32}$");
    static final String USERNAME_RULE = "Username must be 3-32 characters: letters, digits, dot, underscore or hyphen";

    /**
     * Create account (requires email verification)
     */
    @Transactional
    public AccountDTO createAccount(String username, String email, String password) {
        email = EmailAddress.normalize(email); // first, so " Victim@x.com" passes the format check and meets victim@x.com (Obsidian 74)
        // Stricter than RFC 5321 on purpose: no "/", spaces or quotes, so the email is safe wherever it ends up
        // (a URL path to bank, a log line). Scenario A9.
        if (email == null || !EMAIL_FORMAT.matcher(email).matches()) {
            throw new IllegalArgumentException("Please enter a valid email address");
        }
        // Before anything about the email's state is said, and before bank is called (Obsidian 78)
        username = username == null ? null : username.trim();
        if (username == null || !USERNAME_FORMAT.matcher(username).matches()) {
            throw new IllegalArgumentException(USERNAME_RULE);
        }
        Optional<Account> existing = accountRepository.findByEmail(email);
        if (existing.isPresent()) {
            if (isClaimed(existing.get())) {
                throw new IllegalArgumentException("Email already exists!");
            }
            // Someone registered this address and never proved it: maybe its owner, maybe a squatter (Obsidian 58).
            // A password reset claims it, because the reset code only reaches the owner's inbox.
            throw new EmailNotVerifiedException();
        }
        
        // Check if username already exists
        if (accountRepository.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException("Username already exists!");
        }
        
        // Create Account entity object
        Account account = new Account();
        account.setUsername(username);
        account.setEmail(email);
        String encodedPassword = passwordEncoder.encode(password);
        account.setPassword(encodedPassword);
        account.setEmailVerified(false); // Initial state is unverified
        account.setActive(false); // Initial state is inactive

        // Save to database
        Account savedAccount = accountRepository.save(account);
        // Open account immediately after registration (default balance 10000) and bind to account
        try {
            String acctNo = bankAdapter.createCustomerAccount(email, new BigDecimal("10000"));
            if (acctNo != null) {
                savedAccount.setBankAccountNumber(acctNo);
                savedAccount = accountRepository.save(savedAccount);
            }
        } catch (Exception ignore) {}

        return new AccountDTO(
                savedAccount.getUsername(),
                savedAccount.getEmail(),
                "********" // Do not return real password
        );
    }

    /**
     * The username half of an identifier lookup, trimmed as createAccount trims it: someone who signed up as
     * "  bob " (stored "bob") can type it the same way at log-in (review 79 🟡1). The email half is normalised by
     * AccountRepository#findByEmail.
     */
    private Optional<Account> byUsername(String identifier) {
        return accountRepository.findByUsername(identifier == null ? null : identifier.trim());
    }

    /** Sign-up found the address registered but not verified: AccountController tells the user how to claim it */
    public static class EmailNotVerifiedException extends IllegalArgumentException {
        public EmailNotVerifiedException() {
            super("This email is registered but not verified. If it is yours, reset the password to claim it.");
        }
    }

    /** Active and verified: someone proved this address. Until then the account does not own it (Obsidian 58) */
    private static boolean isClaimed(Account account) {
        return Boolean.TRUE.equals(account.getActive()) && Boolean.TRUE.equals(account.getEmailVerified());
    }

    /**
     * Proving the address (the registration code in activateAccount, or the reset code in resetPassword) makes the
     * account usable, and opens its bank account (default balance 10000) if sign-up could not.
     * In resetPassword this runs inside its transaction, so a slow bank holds a database connection meanwhile (up to
     * 10 s), and a bank account made just before a failed commit is left behind. Only when sign-up could not reach bank;
     * rare, recorded (review 59 🟡3).
     */
    private void markEmailProved(Account account) {
        account.setEmailVerified(true);
        account.setActive(true);
        if (account.getBankAccountNumber() == null || account.getBankAccountNumber().isEmpty()) {
            try {
                String acctNo = bankAdapter.createCustomerAccount(account.getEmail(), new java.math.BigDecimal("10000"));
                if (acctNo != null) account.setBankAccountNumber(acctNo);
            } catch (Exception ignore) {}
        }
    }

    /** What activateAccount found; AccountController turns it into the HTTP answer */
    public enum ActivationResult {
        /** the code was right, the account is active now */
        ACTIVATED,
        /** it was active before; nothing changed and email was not asked */
        ALREADY_ACTIVE,
        /** no code, a wrong or expired code, or no such account: the user's mistake (400) */
        INVALID_CODE,
        /** email refused store's token, is down or did not answer in time: not the user's fault (503) */
        EMAIL_UNAVAILABLE
    }

    /**
     * Activate an account with the code from the registration email (A11). Before A11 the browser checked the code
     * with email itself and then told store "activate this email"; store could not tell whether that happened, so
     * anyone could activate any address. Now store asks email, with its service token, the same way resetPassword does.
     *
     * Not @Transactional on purpose: no database transaction stays open while store waits up to 5 seconds for email.
     * (spring.jpa.open-in-view is on, so the request may still hold its JDBC connection meanwhile: review 51 🟡11,
     * not checked yet, a separate piece of work.)
     * Two activations with the same code race safely because email locks the code row and uses it up once.
     * If saving fails after email said yes, the code is already used up: the user asks for a new one (V9).
     *
     * The account's password is whatever was typed at sign-up. That is safe only because the verification page is
     * reached by signing up successfully, i.e. by the person who typed it. Do not give an unverified account another
     * way to this page (a "verify now" button on the log-in page, say): if a squatter registered the address, its owner
     * would activate the squatter's password. Send such users to the password reset instead, which claims the account
     * with a password they choose (Obsidian 58).
     * The same hole by timing: this method reads the account, waits for email, then saves the whole entity. A reset
     * that commits in between (the owner claiming it with a new password) would be overwritten with the old password.
     * Only reachable by using a registration code and a reset code at once, which a squatter cannot arrange (review 59 🟡4).
     */
    public ActivationResult activateAccount(String email, String code) {
        email = EmailAddress.normalize(email); // email keeps the code under the spelling it was sent to (Obsidian 74)
        if (code == null || code.isBlank()) {
            return ActivationResult.INVALID_CODE;
        }
        Optional<Account> accountOpt = accountRepository.findByEmail(email);
        if (accountOpt.isEmpty()) {
            return ActivationResult.INVALID_CODE; // do not spend someone's code on an account that does not exist
        }
        Account account = accountOpt.get();
        if (Boolean.TRUE.equals(account.getActive()) && Boolean.TRUE.equals(account.getEmailVerified())) {
            return ActivationResult.ALREADY_ACTIVE; // a second click or a page reload must not use up a code
        }

        try {
            CodeCheckResponse response = emailRestTemplate.postForObject(
                    emailServiceUrl + "/api/email/verify-code", new CodeCheckRequest(email, code), CodeCheckResponse.class);
            if (response == null || !response.success) {
                return codeRefused(email);
            }
        } catch (HttpClientErrorException.Unauthorized e) {
            alertEmailAuthFailed("verify-code");
            return ActivationResult.EMAIL_UNAVAILABLE;
        } catch (HttpClientErrorException.BadRequest e) {
            return codeRefused(email); // email answers 400 for a wrong or expired code, and only for that
        } catch (HttpClientErrorException e) {
            // Any other 4xx (404: email.service.url points somewhere else) is not the user's mistake (review 51 🟡1)
            logger.error("Activation: email answered {} to verify-code; check email.service.url", e.getStatusCode());
            return ActivationResult.EMAIL_UNAVAILABLE;
        } catch (Exception e) {
            logger.warn("Activation could not reach email: {}", e.getMessage());
            return ActivationResult.EMAIL_UNAVAILABLE;
        }

        markEmailProved(account);
        accountRepository.save(account);
        return ActivationResult.ACTIVATED;
    }

    /**
     * email said no. If the account is active by now, the same code was used a moment ago by a double click or a
     * second tab, and that request activated it: say so instead of "wrong code" (review 51 🟡8).
     */
    private ActivationResult codeRefused(String email) {
        boolean activeNow = accountRepository.findByEmail(email)
                .map(a -> Boolean.TRUE.equals(a.getActive()) && Boolean.TRUE.equals(a.getEmailVerified()))
                .orElse(false);
        return activeNow ? ActivationResult.ALREADY_ACTIVE : ActivationResult.INVALID_CODE;
    }

    /** What sendVerificationCode did; AccountController turns it into the HTTP answer */
    public enum SendCodeResult {
        /** email sent a new registration code */
        SENT,
        /** no account with this email, or it is active already: nothing sent, same answer as SENT */
        NOTHING_TO_SEND,
        /** email refused store's token, failed to send, is down or slow: not the user's fault (503) */
        EMAIL_UNAVAILABLE
    }

    /**
     * Ask email to send a registration code (rate limits, Obsidian 54). The browser used to call email's
     * send-verification itself; email knows no accounts, so anyone could make it mail any address. Now store
     * looks first and only asks email for an account that exists and is not active yet, with its service token.
     */
    public SendCodeResult sendVerificationCode(String email) {
        email = EmailAddress.normalize(email);
        Optional<Account> accountOpt = accountRepository.findByEmail(email);
        if (accountOpt.isEmpty()) {
            return SendCodeResult.NOTHING_TO_SEND;
        }
        Account account = accountOpt.get();
        if (Boolean.TRUE.equals(account.getActive()) && Boolean.TRUE.equals(account.getEmailVerified())) {
            return SendCodeResult.NOTHING_TO_SEND;
        }
        try {
            PasswordResetResponse response = emailRestTemplate.postForObject(
                    emailServiceUrl + "/api/email/send-verification", new PasswordResetRequest(email),
                    PasswordResetResponse.class);
            if (response == null || !response.success) {
                logger.error("Send verification: email answered without success");
                return SendCodeResult.EMAIL_UNAVAILABLE;
            }
            return SendCodeResult.SENT;
        } catch (HttpClientErrorException.Unauthorized e) {
            alertEmailAuthFailed("send-verification");
            return SendCodeResult.EMAIL_UNAVAILABLE;
        } catch (Exception e) {
            // email answers 400 when its own SMTP send fails: that is email's problem too, not the user's
            logger.warn("Send verification: email failed: {}", e.getMessage());
            return SendCodeResult.EMAIL_UNAVAILABLE;
        }
    }

    /**
     * Verify login (only allow verified accounts to login)
     */
    public boolean verifyLogin(String identifier, String password) {
        Optional<Account> userOpt = accountRepository.findByEmail(identifier);
        if (!userOpt.isPresent()) {
            userOpt = byUsername(identifier);
        }
        if (userOpt.isPresent()) {
            Account user = userOpt.get();
            // Check if account is active and email is verified
            if (!Boolean.TRUE.equals(user.getActive()) || !Boolean.TRUE.equals(user.getEmailVerified())) {
                return false;
            }
            return passwordEncoder.matches(password, user.getPassword());
        }
        return false;
    }

    /**
     * Get account information by email
     */
    public Optional<Account> getAccountByEmail(String email) {
        return accountRepository.findByEmail(email);
    }

    public Optional<Account> getAccountByIdentifier(String identifier) {
        Optional<Account> opt = accountRepository.findByEmail(identifier);
        if (!opt.isPresent()) opt = byUsername(identifier);
        return opt;
    }

    /**
     * Check if email exists
     */
    public boolean emailExists(String email) {
        return accountRepository.findByEmail(email).isPresent();
    }

    /**
     * Check if email is verified
     */
    public boolean isEmailVerified(String identifier) {
        Optional<Account> accountOpt = accountRepository.findByEmail(identifier);
        if (!accountOpt.isPresent()) accountOpt = byUsername(identifier);
        return accountOpt.isPresent() && Boolean.TRUE.equals(accountOpt.get().getEmailVerified());
    }

    /**
     * Check if account is active
     */
    public boolean isAccountActive(String identifier) {
        Optional<Account> accountOpt = accountRepository.findByEmail(identifier);
        if (!accountOpt.isPresent()) accountOpt = byUsername(identifier);
        return accountOpt.isPresent() && Boolean.TRUE.equals(accountOpt.get().getActive());
    }

    /**
     * Send password reset email
     */
    public boolean sendPasswordResetEmail(String email) {
        email = EmailAddress.normalize(email);
        // Check if email exists
        if (!emailExists(email)) {
            return false;
        }

        try {
            // Call email service to send password reset verification code
            String url = emailServiceUrl + "/api/email/send-password-reset";
            PasswordResetRequest request = new PasswordResetRequest(email);
            
            PasswordResetResponse response = emailRestTemplate.postForObject(url, request, PasswordResetResponse.class);
            return response != null && response.success;
        } catch (HttpClientErrorException.Unauthorized e) {
            alertEmailAuthFailed("send-password-reset");
            return false;
        } catch (Exception e) {
            System.err.println("Failed to send password reset email: " + e.getMessage());
            return false;
        }
    }

    /**
     * Reset password. On an account that was never verified this also claims it (Obsidian 58): the reset code reached
     * this inbox, which proves the address as well as a registration code does, and the password is the one the
     * owner just chose, so whoever registered the address first (a squatter, say) can no longer log in with theirs.
     */
    @Transactional
    public boolean resetPassword(String email, String code, String newPassword) {
        email = EmailAddress.normalize(email);
        // Check if email exists
        if (!emailExists(email)) {
            return false;
        }

        try {
            // Verify reset code
            String url = emailServiceUrl + "/api/email/verify-password-reset";
            CodeCheckRequest request = new CodeCheckRequest(email, code);
            
            CodeCheckResponse response = emailRestTemplate.postForObject(url, request, CodeCheckResponse.class);
            if (response == null || !response.success) {
                return false;
            }

            // Update password
            Optional<Account> accountOpt = accountRepository.findByEmail(email);
            if (accountOpt.isPresent()) {
                Account account = accountOpt.get();
                String encodedPassword = passwordEncoder.encode(newPassword);
                account.setPassword(encodedPassword);
                if (!isClaimed(account)) {
                    markEmailProved(account);
                }
                accountRepository.save(account);
                return true;
            }
            return false;
        } catch (HttpClientErrorException.Unauthorized e) {
            // The code was NOT checked, so the password must not change: return before the update above
            alertEmailAuthFailed("verify-password-reset");
            return false;
        } catch (Exception e) {
            System.err.println("Password reset failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * email refused store's service token (wrong or missing in store's config). The user only sees "failed";
     * without this line nobody would know every password reset and every activation is broken until the token is fixed.
     */
    private static void alertEmailAuthFailed(String endpoint) {
        logger.error("ALERT email_auth_failed endpoint={} (email refused store's service token; "
                + "check email.service-token)", endpoint);
    }

    // Inner class for API calls. {email}: also the body of email's send-verification
    private static class PasswordResetRequest {
        public String email;
        
        public PasswordResetRequest(String email) {
            this.email = email;
        }
    }

    private static class PasswordResetResponse {
        public boolean success;
        public String message;
    }

    /** Body of email's verify-code and verify-password-reset */
    private static class CodeCheckRequest {
        public String email;
        public String code;
        
        public CodeCheckRequest(String email, String code) {
            this.email = email;
            this.code = code;
        }
    }

    private static class CodeCheckResponse {
        public boolean success;
        public String message;
    }
}
