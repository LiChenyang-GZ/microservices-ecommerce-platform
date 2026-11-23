package com.comp5348.email.repository;

import com.comp5348.email.model.EmailOutbox;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

import java.util.Optional;

public interface EmailOutboxRepository extends JpaRepository<EmailOutbox, Long> {
    Optional<EmailOutbox> findByEmail(String email);
    
    EmailOutbox findByEmailAndCodeAndStatus(String email, String code, String status);
    
    EmailOutbox findByEmailAndStatus(String email, String status);
    
    /**
     * The live codes of one kind for one address, locked (SELECT ... FOR UPDATE) until the transaction ends.
     * Two guesses arriving together then take turns: the attempt counter cannot lose an update, and a right code
     * cannot be used twice (A11, V13).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from EmailOutbox o where o.email = :email and o.verificationType = :type and o.status in :statuses")
    List<EmailOutbox> lockCodes(@Param("email") String email, @Param("type") String verificationType,
                                @Param("statuses") Collection<String> statuses);

    /** A new code replaces the old ones: only one live code per address and kind at a time (A11, V14). */
    @Transactional
    @Modifying
    @Query("update EmailOutbox o set o.status = 'EXPIRED' where o.email = :email and o.verificationType = :type"
            + " and o.status in ('PENDING', 'SENT')")
    int expireLiveCodes(@Param("email") String email, @Param("type") String verificationType);
}
