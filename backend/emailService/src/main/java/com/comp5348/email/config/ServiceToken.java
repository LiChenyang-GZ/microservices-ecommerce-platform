package com.comp5348.email.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The shared secret store must send on email's service-only endpoints (today: the two password-reset calls
 * from AccountService). email's own key, separate from bank's and delivery's.
 *
 * Read from email.service-token, which deployments fill from the EMAIL_SERVICE_TOKEN env var.
 * Blank or missing → email refuses to start, so a missing env var shows up at deploy time,
 * not as "forgot password" silently failing later.
 */
@Component
public class ServiceToken {

    public static final String PROPERTY = "email.service-token";
    public static final String HEADER = "X-Service-Token";

    private final byte[] expected;

    public ServiceToken(@Value("${" + PROPERTY + ":}") String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(PROPERTY + " is not set (env var EMAIL_SERVICE_TOKEN); "
                    + "email will not start without the service token store uses to call it");
        }
        this.expected = token.getBytes(StandardCharsets.UTF_8);
    }

    /** Constant-time comparison (MessageDigest.isEqual): reply time does not reveal how much of a guess was right. */
    public boolean matches(String presented) {
        if (presented == null) return false;
        return MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8));
    }
}
