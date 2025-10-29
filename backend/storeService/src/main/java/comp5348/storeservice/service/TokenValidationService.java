package comp5348.storeservice.service;

import comp5348.storeservice.dto.TokenValidationResponse;
import comp5348.storeservice.utils.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Token 验证服务
 * 提供统一的 token 验证功能，供其他服务调用
 */
@Service
public class TokenValidationService {

    @Autowired
    private JwtUtil jwtUtil;

    /**
     * 验证 token 是否有效
     * @param token JWT token
     * @return 验证结果，包含用户信息
     */
    public TokenValidationResponse validateToken(String token) {
        try {
            // 验证 token 是否有效且未过期
            if (token == null || token.isEmpty()) {
                return TokenValidationResponse.failure("Token is null or empty");
            }

            if (!jwtUtil.validateToken(token)) {
                return TokenValidationResponse.failure("Token is invalid or expired");
            }

            // 提取用户信息
            String email = jwtUtil.extractEmail(token);
            Long userId = jwtUtil.extractUserId(token);

            return TokenValidationResponse.success(userId, email);
        } catch (Exception e) {
            return TokenValidationResponse.failure("Token validation failed: " + e.getMessage());
        }
    }
}
