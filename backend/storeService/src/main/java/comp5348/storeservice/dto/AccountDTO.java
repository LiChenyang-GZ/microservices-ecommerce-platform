package comp5348.storeservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * UserDTO 用于在前后端之间传输用户数据。
 * 这个类不直接暴露数据库模型中的敏感字段（比如密码）。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class AccountDTO {
    private String firstName;
    private String lastName;
    private String email;
    private String password;
}