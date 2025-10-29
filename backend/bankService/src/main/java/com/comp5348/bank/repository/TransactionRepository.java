package com.comp5348.bank.repository;

import com.comp5348.bank.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    
    Optional<Transaction> findByTransactionRef(String transactionRef);
    
    boolean existsByTransactionRef(String transactionRef);
}


