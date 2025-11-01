package com.comp5348.delivery.service.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Get and cache inter-service JWT from StoreService.
 * - Enable condition: store.auth.email and store.auth.password are configured
 * - Expiration strategy: Simple fixed-duration cache (default 12h), or set based on expiresIn in response (if present)
 */
@Component
public class StoreTokenProvider {

    private static final Logger logger = LoggerFactory.getLogger(StoreTokenProvider.class);

    private final RestTemplate restTemplate;

    @Value("${store.auth.url:http://127.0.0.1:8082/api/user/login}")
    private String authUrl;

    @Value("${store.auth.email:}")
    private String email;

    @Value("${store.auth.password:}")
    private String password;

    // Cache
    private volatile String cachedToken;
    private volatile long expireAtEpochMs = 0L;

    public StoreTokenProvider(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public String getToken() {
        if (!enabled()) {
            logger.warn("StoreTokenProvider disabled: missing store.auth.email/password.");
            return null;
        }
            long now = Instant.now().toEpochMilli();
            // Refresh 60 seconds early
        if (cachedToken == null || now + 60_000 >= expireAtEpochMs) {
            synchronized (this) {
                if (cachedToken == null || now + 60_000 >= expireAtEpochMs) {
                    refreshTokenInternal();
                }
            }
        }
        return cachedToken;
    }

    public synchronized void forceRefresh() {
        if (!enabled()) return;
        refreshTokenInternal();
    }

    private boolean enabled() {
        return email != null && !email.isEmpty() && password != null && !password.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private void refreshTokenInternal() {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> body = new HashMap<>();
            body.put("email", email);
            body.put("password", password);
            Map<String, Object> resp = restTemplate.postForObject(authUrl, new HttpEntity<>(body, headers), Map.class);
            if (resp != null) {
                Object tokenObj = resp.get("token");
                if (tokenObj == null) tokenObj = resp.get("data"); // Compatible with some responses that put token in data
                if (tokenObj != null) {
                    this.cachedToken = String.valueOf(tokenObj);
                    // Expiration time prefers returned field, otherwise defaults to 12 hours
                    long now = Instant.now().toEpochMilli();
                    Object exp = resp.get("expiresIn");
                    if (exp instanceof Number) {
                        this.expireAtEpochMs = now + ((Number) exp).longValue();
                    } else {
                        this.expireAtEpochMs = now + 12L * 60L * 60L * 1000L; // 12h
                    }
                    logger.info("Obtained service JWT from Store. Expires at {}", this.expireAtEpochMs);
                    return;
                }
            }
            logger.warn("Login to Store did not return token. Check authUrl/email/password configuration.");
        } catch (Exception e) {
            logger.warn("Failed to obtain service JWT from Store: {}", e.getMessage());
        }
        this.cachedToken = null;
        this.expireAtEpochMs = 0L;
    }
}


