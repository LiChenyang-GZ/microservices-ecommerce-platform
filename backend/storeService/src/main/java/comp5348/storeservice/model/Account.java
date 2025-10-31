package comp5348.storeservice.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Column;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Entity
@Data
public class Account {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Getter
    @Setter
    private String firstName;

    @Getter
    @Setter
    private String lastName;

    @Getter
    @Setter
    private String password;

    @Getter
    @Setter
    @Column(unique = true)
    private String email; // 真实唯一登录标识

    @Getter
    @Setter
    @Column(unique = true)
    private String username; // 用户名（唯一）

    @Getter
    @Setter
    private Boolean emailVerified = false; // 邮箱是否已验证

    @Getter
    @Setter
    private Boolean active = true; // 账户是否激活

  @Getter
  @Setter
  @Column(unique = true)
  private String bankAccountNumber; // 关联银行账户（激活时自动开户）
}






