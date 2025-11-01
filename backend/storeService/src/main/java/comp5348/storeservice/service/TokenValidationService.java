package comp5348.storeservice.service;

import comp5348.storeservice.dto.TokenValidationResponse;
import comp5348.storeservice.utils.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Token Validation Service
 * Provides unified token validation functionality for other services to call
 */
@Service
public class TokenValidationService {

    @Autowired
    private JwtUtil jwtUtil;

    /**
     * Validate whether token is valid
     * @param token JWT token
     * @return Validation result containing user information
     */
    public TokenValidationResponse validateToken(String token) {
        try {
            // Validate whether token is valid and not expired
            if (token == null || token.isEmpty()) {
                return TokenValidationResponse.failure("Token is null or empty");
            }

            if (!jwtUtil.validateToken(token)) {
                return TokenValidationResponse.failure("Token is invalid or expired");
            }

            // Extract user information
            String email = jwtUtil.extractEmail(token);
            Long userId = jwtUtil.extractUserId(token);

            return TokenValidationResponse.success(userId, email);
        } catch (Exception e) {
            return TokenValidationResponse.failure("Token validation failed: " + e.getMessage());
        }
    }
}
