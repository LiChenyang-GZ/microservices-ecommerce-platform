package com.comp5348.bank.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The one shared secret that lets a caller (only store) use bank: gRPC Transfer / Refund and every /api/bank/* REST call.
 *
 * Read from bank.service-token, which deployments fill from the BANK_SERVICE_TOKEN env var (backend/.env.example).
 * Blank or missing → bank refuses to start. A bank that starts without it would look healthy while
 * either every payment fails or, worse, the check is skipped; failing at startup is the only loud option.
 */
@Component
public class ServiceToken {

    public static final String PROPERTY = "bank.service-token";
    /** HTTP header for REST calls; the gRPC metadata key is the same name in lower case (gRPC requires it). */
    public static final String HEADER = "X-Service-Token";

    private final byte[] expected;

    public ServiceToken(@Value("${" + PROPERTY + ":}") String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(PROPERTY + " is not set (env var BANK_SERVICE_TOKEN); "
                    + "bank will not start without the service token store uses to call it");
        }
        this.expected = token.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * True only for the exact token. MessageDigest.isEqual takes the same time however many leading bytes match,
     * so a caller cannot guess the token one byte at a time by timing the replies.
     */
    public boolean matches(String presented) {
        if (presented == null) return false;
        return MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8));
    }
}
