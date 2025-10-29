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
     * 转账 - 支持幂等性
     */
    @Transactional
    public TransferResponse transfer(TransferRequest request) {
        logger.info("Processing transfer: from={}, to={}, amount={}, ref={}", 
                request.getFromAccount(), request.getToAccount(), request.getAmount(), request.getTransactionRef());
        
        try {
            // 幂等性检查：如果交易已存在，返回之前的结果
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
            
            // 验证金额
            if (request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                return createFailedTransaction(request, "Amount must be positive");
            }
            
            // 查找账户
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
            
            // 检查余额
            if (fromAccount.getBalance().compareTo(request.getAmount()) < 0) {
                return createFailedTransaction(request, "Insufficient balance. Available: " + fromAccount.getBalance());
            }
            
            // 执行转账
            fromAccount.setBalance(fromAccount.getBalance().subtract(request.getAmount()));
            toAccount.setBalance(toAccount.getBalance().add(request.getAmount()));
            
            accountRepository.save(fromAccount);
            accountRepository.save(toAccount);
            
            // 创建成功的交易记录
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
     * 退款
     */
    @Transactional
    public RefundResponse refund(RefundRequest request) {
        logger.info("Processing refund: transactionId={}, reason={}", request.getTransactionId(), request.getReason());
        
        try {
            // 查找原交易
            Optional<Transaction> originalTxnOpt = transactionRepository.findById(Long.parseLong(request.getTransactionId()));
            
            if (!originalTxnOpt.isPresent()) {
                return new RefundResponse(false, null, "Original transaction not found");
            }
            
            Transaction originalTxn = originalTxnOpt.get();
            
            if (originalTxn.getStatus() != Transaction.TransactionStatus.SUCCESS) {
                return new RefundResponse(false, null, "Can only refund successful transactions");
            }
            
            // 反向转账
            Optional<BankAccount> fromAccountOpt = accountRepository.findByAccountNumber(originalTxn.getToAccount());
            Optional<BankAccount> toAccountOpt = accountRepository.findByAccountNumber(originalTxn.getFromAccount());
            
            if (!fromAccountOpt.isPresent() || !toAccountOpt.isPresent()) {
                return new RefundResponse(false, null, "Account not found for refund");
            }
            
            BankAccount fromAccount = fromAccountOpt.get();
            BankAccount toAccount = toAccountOpt.get();
            
            // 检查余额（从接收方退款）
            if (fromAccount.getBalance().compareTo(originalTxn.getAmount()) < 0) {
                return new RefundResponse(false, null, "Insufficient balance for refund. Available: " + fromAccount.getBalance());
            }
            
            // 执行退款
            fromAccount.setBalance(fromAccount.getBalance().subtract(originalTxn.getAmount()));
            toAccount.setBalance(toAccount.getBalance().add(originalTxn.getAmount()));
            
            accountRepository.save(fromAccount);
            accountRepository.save(toAccount);
            
            // 创建退款交易记录
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
     * 查询账户余额
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
     * 创建失败的交易记录
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


