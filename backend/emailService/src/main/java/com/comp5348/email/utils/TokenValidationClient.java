package com.comp5348.email.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * Token Validation Client
 * Calls StoreService validation interface
 */
@Component
public class TokenValidationClient {
    
    private static final Logger logger = LoggerFactory.getLogger(TokenValidationClient.class);
    
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    
    @Value("${store.service.url:http://localhost:8082}")
    private String storeServiceUrl;
    
    public TokenValidationClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * Validate token
     */
    public TokenValidationResult validateToken(String token) {
        if (token == null || token.isEmpty()) {
            return TokenValidationResult.invalid("Token is null or empty");
        }
        
        try {
            String url = storeServiceUrl + "/api/user/validate-token";
            
            Map<String, String> requestBody = new HashMap<>();
            requestBody.put("token", token);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            HttpEntity<Map<String, String>> request = new HttpEntity<>(requestBody, headers);
            
            ResponseEntity<TokenValidationResult> response = restTemplate.exchange(
                url,
                HttpMethod.POST,
                request,
                TokenValidationResult.class
            );
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                TokenValidationResult result = response.getBody();
                if (result.isValid()) {
                    logger.debug("Token validation successful for user: {}", result.getEmail());
                } else {
                    logger.warn("Token validation failed: {}", result.getMessage());
                }
                return result;
            } else {
                return TokenValidationResult.invalid("Validation service returned error");
            }
            
        } catch (Exception e) {
            logger.error("Failed to validate token: {}", e.getMessage());
            return TokenValidationResult.invalid("Token validation failed: " + e.getMessage());
        }
    }
    
    public static class TokenValidationResult {
        private boolean valid;
        private Long userId;
        private String email;
        private String message;
        
        public TokenValidationResult() {
        }
        
        public TokenValidationResult(boolean valid, Long userId, String email, String message) {
            this.valid = valid;
            this.userId = userId;
            this.email = email;
            this.message = message;
        }
        
        public static TokenValidationResult valid(Long userId, String email) {
            return new TokenValidationResult(true, userId, email, "Token is valid");
        }
        
        public static TokenValidationResult invalid(String message) {
            return new TokenValidationResult(false, null, null, message);
        }
        
        public boolean isValid() {
            return valid;
        }
        
        public void setValid(boolean valid) {
            this.valid = valid;
        }
        
        public Long getUserId() {
            return userId;
        }
        
        public void setUserId(Long userId) {
            this.userId = userId;
        }
        
        public String getEmail() {
            return email;
        }
        
        public void setEmail(String email) {
            this.email = email;
        }
        
        public String getMessage() {
            return message;
        }
        
        public void setMessage(String message) {
            this.message = message;
        }
    }
}
