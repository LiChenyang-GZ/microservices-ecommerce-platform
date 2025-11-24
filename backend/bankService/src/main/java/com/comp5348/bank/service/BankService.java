package com.comp5348.bank.service;

import com.comp5348.bank.dto.*;
import com.comp5348.bank.model.BankAccount;
import com.comp5348.bank.model.Transaction;
import com.comp5348.bank.repository.BankAccountRepository;
import com.comp5348.bank.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class BankService {
    
    private static final Logger logger = LoggerFactory.getLogger(BankService.class);
    
    @Autowired
    private BankAccountRepository accountRepository;
    
    @Autowired
    private TransactionRepository transactionRepository;
    
    /**
     * Transfer - supports idempotency
     */
    @Transactional
    public TransferResponse transfer(TransferRequest request) {
        logger.info("Processing transfer: from={}, to={}, amount={}, ref={}", 
                request.getFromAccount(), request.getToAccount(), request.getAmount(), request.getTransactionRef());
        
        try {
            // Idempotency check: if transaction already exists, return previous result
            Optional<Transaction> existingTxn = transactionRepository.findByTransactionRef(request.getTransactionRef());
            if (existingTxn.isPresent()) {
                Transaction txn = existingTxn.get();
                logger.info("Transaction already exists with ref={}, status={}", request.getTransactionRef(), txn.getStatus());
                
                if (txn.getStatus() == Transaction.TransactionStatus.SUCCESS) {
                    return new TransferResponse(true, txn.getId().toString(), "Transaction already processed successfully");
                } else if (txn.getStatus() == Transaction.TransactionStatus.FAILED) {
                    return new TransferResponse(false, txn.getId().toString(), txn.getErrorMessage());
                }
            }
            
            // Validate amount
            if (request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                return createFailedTransaction(request, "Amount must be positive");
            }
            
            // Find accounts
            Optional<BankAccount> fromAccountOpt = accountRepository.findByAccountNumber(request.getFromAccount());
            Optional<BankAccount> toAccountOpt = accountRepository.findByAccountNumber(request.getToAccount());
            
            if (!fromAccountOpt.isPresent()) {
                return createFailedTransaction(request, "From account not found: " + request.getFromAccount());
            }
            
            if (!toAccountOpt.isPresent()) {
                return createFailedTransaction(request, "To account not found: " + request.getToAccount());
            }
            
            BankAccount fromAccount = fromAccountOpt.get();
            BankAccount toAccount = toAccountOpt.get();
            
            // Check balance
            if (fromAccount.getBalance().compareTo(request.getAmount()) < 0) {
                return createFailedTransaction(request, "Insufficient balance. Available: " + fromAccount.getBalance());
            }
            
            // Execute transfer
            fromAccount.setBalance(fromAccount.getBalance().subtract(request.getAmount()));
            toAccount.setBalance(toAccount.getBalance().add(request.getAmount()));
            
            accountRepository.save(fromAccount);
            accountRepository.save(toAccount);
            
            // Create successful transaction record
            Transaction transaction = new Transaction();
            transaction.setFromAccount(request.getFromAccount());
            transaction.setToAccount(request.getToAccount());
            transaction.setAmount(request.getAmount());
            transaction.setType(Transaction.TransactionType.TRANSFER);
            transaction.setStatus(Transaction.TransactionStatus.SUCCESS);
            transaction.setTransactionRef(request.getTransactionRef());
            transaction.setCompletedAt(LocalDateTime.now());
            
            Transaction savedTxn = transactionRepository.save(transaction);
            
            logger.info("Transfer successful: txnId={}, ref={}", savedTxn.getId(), request.getTransactionRef());
            
            return new TransferResponse(true, savedTxn.getId().toString(), "Transfer successful");
            
        } catch (Exception e) {
            logger.error("Transfer failed: {}", e.getMessage(), e);
            return createFailedTransaction(request, "Transfer failed: " + e.getMessage());
        }
    }
    
    /**
     * Refund
     */
    @Transactional
    public RefundResponse refund(RefundRequest request) {
        logger.info("Processing refund: transactionId={}, reason={}", request.getTransactionId(), request.getReason());
        
        try {
            // Find original transaction
            Optional<Transaction> originalTxnOpt = transactionRepository.findById(Long.parseLong(request.getTransactionId()));
            
            if (!originalTxnOpt.isPresent()) {
                return new RefundResponse(false, null, "Original transaction not found");
            }
            
            Transaction originalTxn = originalTxnOpt.get();
            
            if (originalTxn.getStatus() != Transaction.TransactionStatus.SUCCESS) {
                return new RefundResponse(false, null, "Can only refund successful transactions");
            }
            
            // Reverse transfer
            Optional<BankAccount> fromAccountOpt = accountRepository.findByAccountNumber(originalTxn.getToAccount());
            Optional<BankAccount> toAccountOpt = accountRepository.findByAccountNumber(originalTxn.getFromAccount());
            
            if (!fromAccountOpt.isPresent() || !toAccountOpt.isPresent()) {
                return new RefundResponse(false, null, "Account not found for refund");
            }
            
            BankAccount fromAccount = fromAccountOpt.get();
            BankAccount toAccount = toAccountOpt.get();
            
            // Check balance (refund from receiver)
            if (fromAccount.getBalance().compareTo(originalTxn.getAmount()) < 0) {
                return new RefundResponse(false, null, "Insufficient balance for refund. Available: " + fromAccount.getBalance());
            }
            
            // Execute refund
            fromAccount.setBalance(fromAccount.getBalance().subtract(originalTxn.getAmount()));
            toAccount.setBalance(toAccount.getBalance().add(originalTxn.getAmount()));
            
            accountRepository.save(fromAccount);
            accountRepository.save(toAccount);
            
            // Create refund transaction record
            Transaction refundTxn = new Transaction();
            refundTxn.setFromAccount(originalTxn.getToAccount());
            refundTxn.setToAccount(originalTxn.getFromAccount());
            refundTxn.setAmount(originalTxn.getAmount());
            refundTxn.setType(Transaction.TransactionType.REFUND);
            refundTxn.setStatus(Transaction.TransactionStatus.SUCCESS);
            refundTxn.setTransactionRef("REFUND-" + originalTxn.getTransactionRef());
            refundTxn.setErrorMessage(request.getReason());
            refundTxn.setCompletedAt(LocalDateTime.now());
            
            Transaction savedRefundTxn = transactionRepository.save(refundTxn);
            
            logger.info("Refund successful: refundTxnId={}, originalTxnId={}", savedRefundTxn.getId(), request.getTransactionId());
            
            return new RefundResponse(true, savedRefundTxn.getId().toString(), "Refund successful");
            
        } catch (Exception e) {
            logger.error("Refund failed: {}", e.getMessage(), e);
            return new RefundResponse(false, null, "Refund failed: " + e.getMessage());
        }
    }
    
    /**
     * Query account balance
     */
    public BalanceResponse getBalance(String accountNumber) {
        logger.info("Querying balance for account: {}", accountNumber);
        
        Optional<BankAccount> accountOpt = accountRepository.findByAccountNumber(accountNumber);
        
        if (!accountOpt.isPresent()) {
            return new BalanceResponse(false, accountNumber, null, "Account not found");
        }
        
        BankAccount account = accountOpt.get();
        return new BalanceResponse(true, accountNumber, account.getBalance(), "Balance retrieved successfully");
    }

    /**
     * Create bank account
     */
    @Transactional
    public BankAccountCreateResponse createAccount(BankAccountCreateRequest req) {
        try {
            BankAccount acc = new BankAccount();
            acc.setAccountNumber(generateAccountNumber());
            acc.setOwnerName(req.getOwnerEmail() != null ? req.getOwnerEmail() : "USER");
            acc.setBalance(req.getInitialBalance() != null ? req.getInitialBalance() : BigDecimal.ZERO);
            accountRepository.save(acc);
            return new BankAccountCreateResponse(true, acc.getAccountNumber(), "Account created");
        } catch (Exception e) {
            logger.error("Create account failed: {}", e.getMessage());
            return new BankAccountCreateResponse(false, null, "Create account failed: " + e.getMessage());
        }
    }

    private String generateAccountNumber() {
        return "ACC-" + System.currentTimeMillis();
    }

    /**
     * Get or create account by owner email
     * Handles race condition where multiple requests try to create account simultaneously
     */
    public BankAccountCreateResponse getOrCreateAccountByEmail(String ownerEmail) {
        try {
            // Try to find existing account first
            Optional<BankAccount> existingAccount = accountRepository.findByOwnerName(ownerEmail);
            if (existingAccount.isPresent()) {
                BankAccount acc = existingAccount.get();
                logger.info("Found existing bank account for user: {}, accountNumber: {}", ownerEmail, acc.getAccountNumber());
                return new BankAccountCreateResponse(true, acc.getAccountNumber(), "Account found");
            }

            // Create new account if not found
            try {
                BankAccount acc = new BankAccount();
                acc.setAccountNumber(generateAccountNumber());
                acc.setOwnerName(ownerEmail);
                acc.setBalance(BigDecimal.ZERO);
                accountRepository.save(acc);
                logger.info("Created new bank account for user: {}, accountNumber: {}", ownerEmail, acc.getAccountNumber());
                return new BankAccountCreateResponse(true, acc.getAccountNumber(), "Account created");
            } catch (Exception createException) {
                // Handle race condition: if account creation failed due to duplicate key,
                // it means another thread created it, so try to find it again
                logger.warn("Account creation failed for {}, trying to find existing account again: {}",
                    ownerEmail, createException.getMessage());

                Optional<BankAccount> retryAccount = accountRepository.findByOwnerName(ownerEmail);
                if (retryAccount.isPresent()) {
                    BankAccount acc = retryAccount.get();
                    logger.info("Found account after creation conflict for user: {}, accountNumber: {}",
                        ownerEmail, acc.getAccountNumber());
                    return new BankAccountCreateResponse(true, acc.getAccountNumber(), "Account found");
                }

                // If still not found, throw the original exception
                throw createException;
            }
        } catch (Exception e) {
            logger.error("Get or create account failed for {}: {}", ownerEmail, e.getMessage(), e);
            return new BankAccountCreateResponse(false, null, "Failed to get or create account: " + e.getMessage());
        }
    }
    
    /**
     * Create failed transaction record
     */
    private TransferResponse createFailedTransaction(TransferRequest request, String errorMessage) {
        try {
            Transaction transaction = new Transaction();
            transaction.setFromAccount(request.getFromAccount());
            transaction.setToAccount(request.getToAccount());
            transaction.setAmount(request.getAmount());
            transaction.setType(Transaction.TransactionType.TRANSFER);
            transaction.setStatus(Transaction.TransactionStatus.FAILED);
            transaction.setTransactionRef(request.getTransactionRef());
            transaction.setErrorMessage(errorMessage);
            transaction.setCompletedAt(LocalDateTime.now());
            
            Transaction savedTxn = transactionRepository.save(transaction);
            
            logger.warn("Transfer failed: txnId={}, error={}", savedTxn.getId(), errorMessage);
            
            return new TransferResponse(false, savedTxn.getId().toString(), errorMessage);
        } catch (Exception e) {
            logger.error("Failed to save failed transaction: {}", e.getMessage());
            return new TransferResponse(false, null, errorMessage);
        }
    }
}


