package comp5348.storeservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * UserDTO used for transferring user data between frontend and backend.
 * This class does not directly expose sensitive fields from the database model (such as passwords).
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class AccountDTO {
    private String username;
    private String email;
    private String password;
}