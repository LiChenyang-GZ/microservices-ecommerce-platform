package comp5348.storeservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Token 验证响应 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TokenValidationResponse {
    private boolean valid;
    private Long userId;
    private String email;
    private String message;
    
    public static TokenValidationResponse success(Long userId, String email) {
        return new TokenValidationResponse(true, userId, email, "Token is valid");
    }
    
    public static TokenValidationResponse failure(String message) {
        return new TokenValidationResponse(false, null, null, message);
    }
}
