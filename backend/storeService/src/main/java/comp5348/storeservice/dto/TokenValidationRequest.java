package comp5348.storeservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Token validation request DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TokenValidationRequest {
    private String token;
}
