package com.comp5348.email.repository;

import com.comp5348.email.model.EmailOutbox;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmailOutboxRepository extends JpaRepository<EmailOutbox, Long> {
    Optional<EmailOutbox> findByEmail(String email);
    
    EmailOutbox findByEmailAndCodeAndStatus(String email, String code, String status);
    
    EmailOutbox findByEmailAndStatus(String email, String status);
    
    Optional<EmailOutbox> findByEmailAndVerificationTypeAndStatus(String email, String verificationType, String status);
}
