package comp5348.storeservice.service;


import comp5348.storeservice.config.ServiceTokens;
import comp5348.storeservice.model.Account;
import comp5348.storeservice.repository.AccountRepository;
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
     * Create account (requires email verification)
     */
    @Transactional
    public AccountDTO createAccount(String username, String email, String password) {
        // Stricter than RFC 5321 on purpose: no "/", spaces or quotes, so the email is safe wherever it ends up
        // (a URL path to bank, a log line). Scenario A9.
        if (email == null || !EMAIL_FORMAT.matcher(email).matches()) {
            throw new IllegalArgumentException("Please enter a valid email address");
        }
        if (accountRepository.findByEmail(email).isPresent()) {
            throw new IllegalArgumentException("Email already exists!");
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
     */
    public ActivationResult activateAccount(String email, String code) {
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

        account.setEmailVerified(true);
        account.setActive(true);
        // Open account for user upon activation (default balance 10000), only if bank account is not yet bound
        if (account.getBankAccountNumber() == null || account.getBankAccountNumber().isEmpty()) {
            try {
                String acctNo = bankAdapter.createCustomerAccount(account.getEmail(), new java.math.BigDecimal("10000"));
                if (acctNo != null) account.setBankAccountNumber(acctNo);
            } catch (Exception ignore) {}
        }
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
            userOpt = accountRepository.findByUsername(identifier);
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
        if (!opt.isPresent()) opt = accountRepository.findByUsername(identifier);
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
        if (!accountOpt.isPresent()) accountOpt = accountRepository.findByUsername(identifier);
        return accountOpt.isPresent() && Boolean.TRUE.equals(accountOpt.get().getEmailVerified());
    }

    /**
     * Check if account is active
     */
    public boolean isAccountActive(String identifier) {
        Optional<Account> accountOpt = accountRepository.findByEmail(identifier);
        if (!accountOpt.isPresent()) accountOpt = accountRepository.findByUsername(identifier);
        return accountOpt.isPresent() && Boolean.TRUE.equals(accountOpt.get().getActive());
    }

    /**
     * Send password reset email
     */
    public boolean sendPasswordResetEmail(String email) {
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
     * Reset password
     */
    @Transactional
    public boolean resetPassword(String email, String code, String newPassword) {
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
