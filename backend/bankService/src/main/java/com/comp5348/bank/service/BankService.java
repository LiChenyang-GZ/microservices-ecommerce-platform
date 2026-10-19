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
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
public class BankService {

    private static final Logger logger = LoggerFactory.getLogger(BankService.class);

    @Autowired
    private BankAccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    /** Answer for a reused key with different content. A rejection, so store never records it as paid / refunded. */
    static final String KEY_CONFLICT = "Idempotency key conflict: this key was already used for a different request";

    /**
     * Transfer, deduplicated by idempotency key.
     *
     * Order of steps matters:
     * 1. Key already seen → same request: return the first result, move no money.
     *    Different request under the same key (someone else used it first) → key conflict, never success.
     * 2. Lock both account rows (SELECT ... FOR UPDATE), in account-number order.
     * 3. Check the key again. A request with the same key may have committed while we waited for the lock;
     *    now we can see it and return its result instead of failing on the unique constraint.
     * 4. Check the balance and move the money. Nobody else can change these two balances until we commit.
     */
    @Transactional
    public TransferResponse transfer(TransferRequest request) {
        String key = request.getIdempotencyKey();
        logger.info("Processing transfer: key={}, from={}, to={}, amount={}",
                key, request.getFromAccount(), request.getToAccount(), request.getAmount());

        if (key == null || key.isBlank()) {
            return new TransferResponse(false, null, "idempotencyKey is required");
        }

        Optional<TransferResponse> previous = previousTransferResult(key, request);
        if (previous.isPresent()) {
            return previous.get();
        }

        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            return createFailedTransaction(request, "Amount must be positive");
        }

        Map<String, BankAccount> locked = lockAccounts(request.getFromAccount(), request.getToAccount());

        previous = previousTransferResult(key, request);
        if (previous.isPresent()) {
            return previous.get();
        }

        BankAccount fromAccount = locked.get(request.getFromAccount());
        BankAccount toAccount = locked.get(request.getToAccount());
        if (fromAccount == null) {
            return createFailedTransaction(request, "From account not found: " + request.getFromAccount());
        }
        if (toAccount == null) {
            return createFailedTransaction(request, "To account not found: " + request.getToAccount());
        }

        if (fromAccount.getBalance().compareTo(request.getAmount()) < 0) {
            return createFailedTransaction(request, "Insufficient balance. Available: " + fromAccount.getBalance());
        }

        fromAccount.setBalance(fromAccount.getBalance().subtract(request.getAmount()));
        toAccount.setBalance(toAccount.getBalance().add(request.getAmount()));
        accountRepository.save(fromAccount);
        accountRepository.save(toAccount);

        Transaction transaction = new Transaction();
        transaction.setFromAccount(request.getFromAccount());
        transaction.setToAccount(request.getToAccount());
        transaction.setAmount(request.getAmount());
        transaction.setType(Transaction.TransactionType.TRANSFER);
        transaction.setStatus(Transaction.TransactionStatus.SUCCESS);
        transaction.setTransactionRef(key);
        transaction.setCompletedAt(LocalDateTime.now());
        Transaction savedTxn = transactionRepository.save(transaction);

        logger.info("Transfer successful: txnId={}, key={}", savedTxn.getId(), key);
        return new TransferResponse(true, savedTxn.getId().toString(), "Transfer successful");
    }

    /**
     * Full refund of an earlier transfer, deduplicated by idempotency key (store sends REFUND-{paymentId}-{random}).
     * Same steps as transfer: check key → lock both accounts → check key again → move money.
     * A rejected refund is not recorded, so the same key can be retried after someone fixes the cause.
     */
    @Transactional
    public RefundResponse refund(RefundRequest request) {
        String key = request.getIdempotencyKey();
        logger.info("Processing refund: key={}, originalTransactionId={}, reason={}",
                key, request.getOriginalTransactionId(), request.getReason());

        if (key == null || key.isBlank()) {
            return new RefundResponse(false, null, "idempotencyKey is required");
        }

        Long originalId;
        try {
            originalId = Long.parseLong(request.getOriginalTransactionId());
        } catch (NumberFormatException e) {
            return new RefundResponse(false, null, "Invalid originalTransactionId: " + request.getOriginalTransactionId());
        }

        Optional<RefundResponse> previous = previousRefundResult(key, originalId);
        if (previous.isPresent()) {
            return previous.get();
        }
        Optional<Transaction> originalOpt = transactionRepository.findById(originalId);
        if (originalOpt.isEmpty()) {
            return new RefundResponse(false, null, "Original transaction not found");
        }
        Transaction original = originalOpt.get();
        if (original.getType() != Transaction.TransactionType.TRANSFER
                || original.getStatus() != Transaction.TransactionStatus.SUCCESS) {
            return new RefundResponse(false, null, "Can only refund successful transfers");
        }

        Map<String, BankAccount> locked = lockAccounts(original.getFromAccount(), original.getToAccount());

        previous = previousRefundResult(key, originalId);
        if (previous.isPresent()) {
            return previous.get();
        }
        // The same transfer was already refunded under another key: give back that refund, never pay twice
        Optional<Transaction> earlierRefund = transactionRepository.findFirstByOriginalTransactionIdAndTypeAndStatus(
                originalId, Transaction.TransactionType.REFUND, Transaction.TransactionStatus.SUCCESS);
        if (earlierRefund.isPresent()) {
            logger.info("Transfer {} already refunded by txnId={}, key={}", originalId, earlierRefund.get().getId(), key);
            return new RefundResponse(true, earlierRefund.get().getId().toString(), "Transaction already refunded");
        }

        // Money goes back from the receiver (store) to the payer (customer)
        BankAccount payer = locked.get(original.getFromAccount());
        BankAccount receiver = locked.get(original.getToAccount());
        if (payer == null || receiver == null) {
            return new RefundResponse(false, null, "Account not found for refund");
        }
        if (receiver.getBalance().compareTo(original.getAmount()) < 0) {
            return new RefundResponse(false, null, "Insufficient balance for refund. Available: " + receiver.getBalance());
        }

        receiver.setBalance(receiver.getBalance().subtract(original.getAmount()));
        payer.setBalance(payer.getBalance().add(original.getAmount()));
        accountRepository.save(receiver);
        accountRepository.save(payer);

        Transaction refundTxn = new Transaction();
        refundTxn.setFromAccount(original.getToAccount());
        refundTxn.setToAccount(original.getFromAccount());
        refundTxn.setAmount(original.getAmount());
        refundTxn.setType(Transaction.TransactionType.REFUND);
        refundTxn.setStatus(Transaction.TransactionStatus.SUCCESS);
        refundTxn.setTransactionRef(key);
        refundTxn.setOriginalTransactionId(originalId);
        refundTxn.setErrorMessage(request.getReason());
        refundTxn.setCompletedAt(LocalDateTime.now());
        Transaction savedRefundTxn = transactionRepository.save(refundTxn);

        logger.info("Refund successful: refundTxnId={}, key={}, originalTxnId={}", savedRefundTxn.getId(), key, originalId);
        return new RefundResponse(true, savedRefundTxn.getId().toString(), "Refund successful");
    }

    /**
     * Lock the given accounts with SELECT ... FOR UPDATE, always in account-number order.
     * Fixed order prevents deadlock: if A→B locked A then B while B→A locked B then A, each would wait for the other.
     * Accounts that do not exist are simply missing from the map.
     */
    private Map<String, BankAccount> lockAccounts(String... accountNumbers) {
        Map<String, BankAccount> locked = new HashMap<>();
        Arrays.stream(accountNumbers)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .forEach(number -> accountRepository.findByAccountNumberForUpdate(number)
                        .ifPresent(account -> locked.put(number, account)));
        return locked;
    }

    /**
     * The key was used before. Only the same request gets the first result back.
     * The same key with different content means someone else got to the key first (keys were guessable before:
     * review finding 1). Answering "success" would tell store it was paid when nothing was taken, so it is a conflict.
     */
    private Optional<TransferResponse> previousTransferResult(String key, TransferRequest request) {
        return transactionRepository.findByTransactionRef(key).map(txn -> {
            if (!sameTransfer(txn, request)) {
                logger.warn("Idempotency key conflict: key={} was used for txnId={} ({} → {}, {}), now {} → {}, {}",
                        key, txn.getId(), txn.getFromAccount(), txn.getToAccount(), txn.getAmount(),
                        request.getFromAccount(), request.getToAccount(), request.getAmount());
                return new TransferResponse(false, null, KEY_CONFLICT);
            }
            logger.info("Transfer key already processed: key={}, txnId={}, status={}", key, txn.getId(), txn.getStatus());
            if (txn.getStatus() == Transaction.TransactionStatus.SUCCESS) {
                return new TransferResponse(true, txn.getId().toString(), "Transaction already processed successfully");
            }
            return new TransferResponse(false, txn.getId().toString(), txn.getErrorMessage());
        });
    }

    private static boolean sameTransfer(Transaction txn, TransferRequest request) {
        return txn.getType() == Transaction.TransactionType.TRANSFER
                && Objects.equals(txn.getFromAccount(), request.getFromAccount())
                && Objects.equals(txn.getToAccount(), request.getToAccount())
                && request.getAmount() != null
                && txn.getAmount().compareTo(request.getAmount()) == 0; // compareTo: 100.0 and 100.00 are the same amount
    }

    /** Same rule for refunds: the key must have been used to refund the same original transaction. */
    private Optional<RefundResponse> previousRefundResult(String key, Long originalId) {
        return transactionRepository.findByTransactionRef(key).map(txn -> {
            boolean sameRefund = txn.getType() == Transaction.TransactionType.REFUND
                    && Objects.equals(txn.getOriginalTransactionId(), originalId);
            if (!sameRefund) {
                logger.warn("Idempotency key conflict: key={} was used for txnId={} (type={}, original={}), now refund of {}",
                        key, txn.getId(), txn.getType(), txn.getOriginalTransactionId(), originalId);
                return new RefundResponse(false, null, KEY_CONFLICT);
            }
            logger.info("Refund key already processed: key={}, txnId={}", key, txn.getId());
            return new RefundResponse(true, txn.getId().toString(), "Refund already processed");
        });
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
     * Record a rejected transfer under its key, so a retry with the same key gets the same rejection.
     * (Pay Now after a rejection uses a new key: PAY-{paymentId}-{attempt + 1}-{new random}.)
     */
    private TransferResponse createFailedTransaction(TransferRequest request, String errorMessage) {
        Transaction transaction = new Transaction();
        transaction.setFromAccount(request.getFromAccount() != null ? request.getFromAccount() : "UNKNOWN");
        transaction.setToAccount(request.getToAccount() != null ? request.getToAccount() : "UNKNOWN");
        transaction.setAmount(request.getAmount() != null ? request.getAmount() : BigDecimal.ZERO);
        transaction.setType(Transaction.TransactionType.TRANSFER);
        transaction.setStatus(Transaction.TransactionStatus.FAILED);
        transaction.setTransactionRef(request.getIdempotencyKey());
        transaction.setErrorMessage(errorMessage);
        transaction.setCompletedAt(LocalDateTime.now());

        Transaction savedTxn = transactionRepository.save(transaction);
        logger.warn("Transfer rejected: txnId={}, key={}, reason={}", savedTxn.getId(), request.getIdempotencyKey(), errorMessage);
        return new TransferResponse(false, savedTxn.getId().toString(), errorMessage);
    }
}
