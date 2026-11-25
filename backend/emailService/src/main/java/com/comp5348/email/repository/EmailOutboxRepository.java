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
     * Two checks arriving together then take turns, so a right code cannot be used twice (A11). Wrong guesses are
     * no longer counted here (Obsidian 70): the only limit on guessing is store's per IP + email count.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    // order by id: every transaction locks the rows in the same order, so two of them cannot deadlock (review 51 🟡6)
    @Query("select o from EmailOutbox o where o.email = :email and o.verificationType = :type and o.status in :statuses"
            + " order by o.id")
    List<EmailOutbox> lockCodes(@Param("email") String email, @Param("type") String verificationType,
                                @Param("statuses") Collection<String> statuses);

    /**
     * A code that was just sent replaces the older ones: only one live code per address and kind (A11, V14).
     * Called after the new code's mail went out, so a resend that fails to send keeps the older code (review 51 🟡2).
     */
    @Transactional
    @Modifying
    @Query("update EmailOutbox o set o.status = 'EXPIRED' where o.email = :email and o.verificationType = :type"
            + " and o.status in ('PENDING', 'SENT') and o.id <> :keepId")
    int expireOtherLiveCodes(@Param("email") String email, @Param("type") String verificationType,
                             @Param("keepId") Long keepId);
}
