package com.comp5348.delivery.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The shared secret store must send on /internal/* calls (today: cancel-by-order). delivery's own key,
 * not bank's: a leaked bank token must not also let someone cancel deliveries.
 *
 * Read from delivery.service-token, which deployments fill from the DELIVERY_SERVICE_TOKEN env var.
 * Blank or missing → delivery refuses to start, so a missing env var is noticed at deploy time,
 * not as every cancel quietly failing later.
 */
@Component
public class ServiceToken {

    public static final String PROPERTY = "delivery.service-token";
    public static final String HEADER = "X-Service-Token";

    private final byte[] expected;

    public ServiceToken(@Value("${" + PROPERTY + ":}") String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(PROPERTY + " is not set (env var DELIVERY_SERVICE_TOKEN); "
                    + "delivery will not start without the service token store uses to call /internal/*");
        }
        this.expected = token.getBytes(StandardCharsets.UTF_8);
    }

    /** Constant-time comparison (MessageDigest.isEqual): reply time does not reveal how much of a guess was right. */
    public boolean matches(String presented) {
        if (presented == null) return false;
        return MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8));
    }
}
