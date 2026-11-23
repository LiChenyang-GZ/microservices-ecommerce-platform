package comp5348.storeservice.ratelimit;

import comp5348.storeservice.ratelimit.RateLimiter.Decision;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

/**
 * The limits on store's public sign-up and log-in endpoints (Obsidian 54, "具体规则").
 *
 *   send-verification, forgot-password: 3 per email per 15 minutes; 20 per IP per hour
 *   activate, reset-password:           20 per IP per 15 minutes
 *   login:                              10 per IP + account per 15 minutes, cleared by a successful login;
 *                                       100 per IP per 15 minutes
 *
 * Each check counts the IP first and stops there if the IP is over: a request blocked by its IP does not use up
 * the email's quota (R9). Each returns the blocking decision, or empty when the request may go on.
 * If Redis does not answer, the rest of the checks are skipped and the request goes on (R19).
 *
 * Known gap (54, R29): someone with many addresses can still use up a victim's email quota or burn their code.
 */
@Component
public class AuthRateLimits {

    private static final Duration FIFTEEN_MINUTES = Duration.ofMinutes(15);
    private static final Duration ONE_HOUR = Duration.ofHours(1);

    static final int SENDS_PER_EMAIL = 3;
    static final int SENDS_PER_IP = 20;
    static final int CODE_CHECKS_PER_IP = 20;
    static final int LOGINS_PER_IP_AND_ACCOUNT = 10;
    static final int LOGINS_PER_IP = 100;

    private final RateLimiter limiter;

    public AuthRateLimits(RateLimiter limiter) {
        this.limiter = limiter;
    }

    public Optional<Decision> sendVerification(String ip, String email) {
        return ipThenEmail("send-verification", ip, email);
    }

    public Optional<Decision> forgotPassword(String ip, String email) {
        return ipThenEmail("forgot-password", ip, email);
    }

    public Optional<Decision> activate(String ip) {
        return blocking(limiter.hit("rl:activate:ip:" + ip, CODE_CHECKS_PER_IP, FIFTEEN_MINUTES));
    }

    public Optional<Decision> resetPassword(String ip) {
        return blocking(limiter.hit("rl:reset-password:ip:" + ip, CODE_CHECKS_PER_IP, FIFTEEN_MINUTES));
    }

    /** Counts this attempt, before the password is checked: a blocked attempt is 429 even with the right password */
    public Optional<Decision> login(String ip, String identifier) {
        Decision byIp = limiter.hit("rl:login:ip:" + ip, LOGINS_PER_IP, FIFTEEN_MINUTES);
        if (byIp.outcome() != RateLimiter.Outcome.ALLOWED) {
            return blocking(byIp);
        }
        return blocking(limiter.hit(loginKey(ip, identifier), LOGINS_PER_IP_AND_ACCOUNT, FIFTEEN_MINUTES));
    }

    /** A successful login: the failed attempts for this IP + account start again from zero (R14) */
    public void loginSucceeded(String ip, String identifier) {
        limiter.clear(loginKey(ip, identifier));
    }

    private Optional<Decision> ipThenEmail(String endpoint, String ip, String email) {
        Decision byIp = limiter.hit("rl:" + endpoint + ":ip:" + ip, SENDS_PER_IP, ONE_HOUR);
        if (byIp.outcome() != RateLimiter.Outcome.ALLOWED) {
            return blocking(byIp);
        }
        return blocking(limiter.hit("rl:" + endpoint + ":email:" + fingerprint(email), SENDS_PER_EMAIL, FIFTEEN_MINUTES));
    }

    private static String loginKey(String ip, String identifier) {
        return "rl:login:ip-account:" + ip + ":" + fingerprint(identifier);
    }

    private static Optional<Decision> blocking(Decision decision) {
        return decision.blocked() ? Optional.of(decision) : Optional.empty();
    }

    /**
     * The same mailbox however it is typed (R7): " Victim@X.com" and "victim@x.com" reach one inbox.
     * Hashed, so Redis holds no email addresses; the IPs stay readable for debugging.
     */
    static String fingerprint(String identifier) {
        String normalised = identifier == null ? "" : identifier.trim().toLowerCase(Locale.ROOT);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(normalised.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JVM", e);
        }
    }
}
