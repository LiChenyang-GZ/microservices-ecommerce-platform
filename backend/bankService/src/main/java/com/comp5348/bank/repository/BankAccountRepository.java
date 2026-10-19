package com.comp5348.bank.repository;

import com.comp5348.bank.model.BankAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BankAccountRepository extends JpaRepository<BankAccount, Long> {

    Optional<BankAccount> findByAccountNumber(String accountNumber);

    Optional<BankAccount> findByOwnerName(String ownerName);

    boolean existsByAccountNumber(String accountNumber);

    /**
     * SELECT ... FOR UPDATE: lock the account row until the transaction ends.
     * Another transaction that wants the same row waits here, then reads the committed balance.
     * Callers lock accounts in account-number order (see BankService.lockAccounts) so two transfers
     * in opposite directions cannot wait for each other forever.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from BankAccount a where a.accountNumber = :accountNumber")
    Optional<BankAccount> findByAccountNumberForUpdate(@Param("accountNumber") String accountNumber);
}
