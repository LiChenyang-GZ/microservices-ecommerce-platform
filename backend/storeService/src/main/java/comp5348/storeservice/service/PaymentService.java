package comp5348.storeservice.service;

import comp5348.storeservice.adapter.BankAdapter;
import comp5348.storeservice.dto.BankRefundRequest;
import comp5348.storeservice.dto.BankRefundResponse;
import comp5348.storeservice.dto.BankTransferRequest;
import comp5348.storeservice.dto.BankTransferResponse;
import comp5348.storeservice.model.Payment;
import comp5348.storeservice.model.PaymentStatus;
import comp5348.storeservice.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentService {
    
    private static final Logger logger = LoggerFactory.getLogger(PaymentService.class);
    
    @Autowired
    private PaymentRepository paymentRepository;
    
    @Autowired
    private BankAdapter bankAdapter;
    
    @Autowired
    private OutboxService outboxService;
    
    @Value("${bank.store.account:STORE_ACCOUNT_001}")
    private String storeAccount;
    
    @Value("${bank.customer.account:CUSTOMER_ACCOUNT_001}")
    private String customerAccount; // Global default (used if user has no account)

    @Autowired
    private comp5348.storeservice.repository.OrderRepository orderRepository;

    @Autowired
    private comp5348.storeservice.repository.AccountRepository accountRepository;
    
    /**
     * Create payment record - idempotency guarantee
     */
    @Transactional
    public Payment createPayment(Long orderId, BigDecimal amount) {
        logger.info("Creating payment for orderId={}, amount={}", orderId, amount);
        
        // Idempotency check
        Optional<Payment> existingPayment = paymentRepository.findByOrderId(orderId);
        if (existingPayment.isPresent()) {
            Payment existing = existingPayment.get();
            logger.info("Payment already exists for orderId={}, status={}", orderId, existing.getStatus());
            
            // Case 1: Already exists but still pending → resend event
            if (existing.getStatus() == PaymentStatus.PENDING) {
                try {
                    outboxService.createPaymentPendingEvent(orderId, existing.getAmount());
                    logger.info("Re-enqueued PAYMENT_PENDING event for orderId={}", orderId);
                } catch (Exception e) {
                    logger.warn("Failed to re-enqueue PAYMENT_PENDING for orderId={}: {}", orderId, e.getMessage());
                }
                return existing;
            }

            // Case 2: Previous failure (e.g., manual DB deletion or bank failure) → reset to pending and resend event
            if (existing.getStatus() == PaymentStatus.FAILED) {
                existing.setStatus(PaymentStatus.PENDING);
                existing.setErrorMessage(null);
                Payment saved = paymentRepository.save(existing);
                try {
                    outboxService.createPaymentPendingEvent(orderId, saved.getAmount());
                    logger.info("Re-enqueued PAYMENT_PENDING after FAILED for orderId={}", orderId);
                } catch (Exception e) {
                    logger.warn("Failed to re-enqueue after FAILED for orderId={}: {}", orderId, e.getMessage());
                }
                return saved;
            }

            // Case 3: Already succeeded (or refunded) → idempotently resend PAYMENT_SUCCESS to avoid downstream event loss
            if (existing.getStatus() == PaymentStatus.SUCCESS) {
                try {
                    outboxService.createPaymentSuccessEvent(orderId, existing.getBankTxnId());
                    logger.info("Re-enqueued PAYMENT_SUCCESS for orderId={}", orderId);
                } catch (Exception e) {
                    logger.warn("Failed to re-enqueue PAYMENT_SUCCESS for orderId={}: {}", orderId, e.getMessage());
                }
            }
            return existing;
        }
        
        Payment payment = new Payment();
        payment.setOrderId(orderId);
        payment.setAmount(amount);
        payment.setStatus(PaymentStatus.PENDING);
        
        Payment savedPayment = paymentRepository.save(payment);
        logger.info("Payment created: id={}, orderId={}", savedPayment.getId(), orderId);
        
        // Create pending payment event to Outbox
        outboxService.createPaymentPendingEvent(orderId, amount);
        
        return savedPayment;
    }
    
    /**
     * Process payment - call Bank service
     */
    @Transactional
    public void processPayment(Long orderId) {
        logger.info("Processing payment for orderId={}", orderId);
        
        Optional<Payment> paymentOpt = paymentRepository.findByOrderId(orderId);
        if (!paymentOpt.isPresent()) {
            logger.error("Payment not found for orderId={}", orderId);
            return;
        }
        
        Payment payment = paymentOpt.get();
        
        // If already processed, do not reprocess
        if (payment.getStatus() != PaymentStatus.PENDING) {
            logger.info("Payment already processed: orderId={}, status={}", orderId, payment.getStatus());
            return;
        }
        
        // Generate unique transaction reference number
        String transactionRef = "TXN-" + orderId + "-" + UUID.randomUUID().toString().substring(0, 8);
        
        // Select payer account: prefer user-specific bank account
        String fromAccount = null;
        try {
            var order = orderRepository.findById(orderId).orElse(null);
            if (order != null) {
                var acc = accountRepository.findById(order.getUserId()).orElse(null);
                if (acc != null && acc.getBankAccountNumber() != null && !acc.getBankAccountNumber().isEmpty()) {
                    fromAccount = acc.getBankAccountNumber();
                }
            }
        } catch (Exception ignore) {}

        if (fromAccount == null || fromAccount.isEmpty()) {
            handlePaymentFailure(payment, "No linked bank account for user");
            return;
        }

        // Call Bank service to transfer
        BankTransferRequest transferRequest = new BankTransferRequest(
                fromAccount,
                storeAccount,
                payment.getAmount(),
                transactionRef
        );
        
        BankTransferResponse response = bankAdapter.transfer(transferRequest);
        
        if (response.isSuccess()) {
            handlePaymentSuccess(payment, response.getTransactionId());
        } else {
            handlePaymentFailure(payment, response.getMessage());
        }
    }
    
    /**
     * Handle payment success
     */
    @Transactional
    public void handlePaymentSuccess(Payment payment, String bankTxnId) {
        logger.info("Payment successful: orderId={}, bankTxnId={}", payment.getOrderId(), bankTxnId);
        
        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setBankTxnId(bankTxnId);
        paymentRepository.save(payment);
        
        // Create payment success event to Outbox (used to trigger delivery)
        outboxService.createPaymentSuccessEvent(payment.getOrderId(), bankTxnId);
        
        logger.info("Payment success event created for orderId={}", payment.getOrderId());
    }
    
    /**
     * Handle payment failure
     */
    @Transactional
    public void handlePaymentFailure(Payment payment, String errorMessage) {
        logger.error("Payment failed: orderId={}, error={}", payment.getOrderId(), errorMessage);
        
        payment.setStatus(PaymentStatus.FAILED);
        payment.setErrorMessage(errorMessage);
        paymentRepository.save(payment);
        
        // Create payment failure event to Outbox (used to release inventory and send notification)
        outboxService.createPaymentFailedEvent(payment.getOrderId(), errorMessage);
        
        logger.info("Payment failure event created for orderId={}", payment.getOrderId());
    }
    
    /**
     * Refund processing
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public Payment refundPayment(Long orderId, String reason) {
        logger.info("Processing refund for orderId={}, reason={}", orderId, reason);
        
        Optional<Payment> paymentOpt = paymentRepository.findByOrderId(orderId);
        if (!paymentOpt.isPresent()) {
            throw new IllegalArgumentException("Payment not found for orderId: " + orderId);
        }
        
        Payment payment = paymentOpt.get();
        
        if (payment.getStatus() != PaymentStatus.SUCCESS) {
            throw new IllegalStateException("Can only refund successful payments. Current status: " + payment.getStatus());
        }
        
        // Call Bank service to refund
        BankRefundRequest refundRequest = new BankRefundRequest(payment.getBankTxnId(), reason);
        BankRefundResponse response = bankAdapter.refund(refundRequest);
        
        if (response.isSuccess()) {
            payment.setStatus(PaymentStatus.REFUNDED);
            payment.setErrorMessage(reason);
            paymentRepository.save(payment);
            
            logger.info("Refund successful: orderId={}, refundTxnId={}", orderId, response.getRefundTransactionId());
            
            // Create refund success event (used to send email notification)
            outboxService.createRefundSuccessEvent(orderId, response.getRefundTransactionId());
        } else {
            logger.error("Refund failed: orderId={}, error={}", orderId, response.getMessage());
            throw new RuntimeException("Refund failed: " + response.getMessage());
        }
        
        return payment;
    }
    
    /**
     * Query payment by orderId
     */
    public Optional<Payment> getPaymentByOrderId(Long orderId) {
        return paymentRepository.findByOrderId(orderId);
    }
    
    /**
     * Query payment by id
     */
    public Optional<Payment> getPaymentById(Long id) {
        return paymentRepository.findById(id);
    }
}


