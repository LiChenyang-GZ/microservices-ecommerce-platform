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
    private String password;

    @Getter
    @Setter
    @Column(unique = true)
    private String email; // Real unique login identifier

    @Getter
    @Setter
    @Column(unique = true)
    private String username; // Username (unique)

    @Getter
    @Setter
    private Boolean emailVerified = false; // Whether email is verified

    @Getter
    @Setter
    private Boolean active = true; // Whether account is active

  @Getter
  @Setter
  @Column(unique = true)
  private String bankAccountNumber; // Associated bank account (automatically opened when activated)
}






