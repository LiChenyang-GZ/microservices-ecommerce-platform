package comp5348.storeservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Token validation response DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TokenValidationResponse {
    private boolean valid;
    private Long userId;
    private String email;
    private String message;
    /** USER or ADMIN (auth PR 3). delivery checks it for its DLQ endpoints; a token without a role claim says USER */
    private String role;
    
    public static TokenValidationResponse success(Long userId, String email, String role) {
        return new TokenValidationResponse(true, userId, email, "Token is valid", role);
    }
    
    public static TokenValidationResponse failure(String message) {
        return new TokenValidationResponse(false, null, null, message, null);
    }
}
