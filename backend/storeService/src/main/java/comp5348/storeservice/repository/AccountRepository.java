package comp5348.storeservice.repository;

import comp5348.storeservice.model.Account;
import comp5348.storeservice.utils.EmailAddress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long>  {

    /**
     * The account for this mailbox however it is typed (Obsidian 74): the read choke point. Account#setEmail stores
     * the same spelling, so the exact match below finds it, and the unique column holds one account per mailbox.
     */
    default Optional<Account> findByEmail(String email) {
        return findByStoredEmail(EmailAddress.normalize(email));
    }

    /** Exact match on the stored column. Call findByEmail instead: it normalises first */
    @Query("select a from Account a where a.email = :email")
    Optional<Account> findByStoredEmail(@Param("email") String email);

    Optional<Account> findByUsername(String username);
}
