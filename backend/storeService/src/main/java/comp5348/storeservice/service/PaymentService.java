package comp5348.storeservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import comp5348.storeservice.model.Order;
import comp5348.storeservice.model.OrderStatus;
import comp5348.storeservice.model.Payment;
import comp5348.storeservice.model.PaymentOutbox;
import comp5348.storeservice.model.PaymentStatus;
import comp5348.storeservice.repository.AccountRepository;
import comp5348.storeservice.repository.OrderRepository;
import comp5348.storeservice.repository.PaymentOutboxRepository;
import comp5348.storeservice.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;

/**
 * Payment state and money tasks. This class never calls bank: it decides what to send and records what came back.
 * OutboxProcessor is the only caller of bank (rule 1). The flow for one PAY task:
 *
 *   startPayAttempt  → (OutboxProcessor calls bank) → onPaySucceeded / onPayRejected / onPayStillUnknown
 *
 * Every method that changes a payment locks its row first (SELECT ... FOR UPDATE). When the order row is also
 * needed, it is locked first, then the payment row, everywhere, so two transactions cannot deadlock on them.
 */
@Service
public class PaymentService {

    private static final Logger logger = LoggerFactory.getLogger(PaymentService.class);

    /** Payment states in which the charge may still go through (so a PAY task is still worth sending) */
    private static final Set<PaymentStatus> CHARGE_OPEN = EnumSet.of(PaymentStatus.PENDING, PaymentStatus.PAY_UNKNOWN);
    /** Payment states in which a refund is still owed */
    private static final Set<PaymentStatus> REFUND_OPEN = EnumSet.of(PaymentStatus.REFUND_PENDING, PaymentStatus.REFUND_UNKNOWN);

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private PaymentOutboxRepository outboxRepository;

    @Value("${bank.store.account:STORE_ACCOUNT_001}")
    private String storeAccount;

    @Value("${payment.bank.slow-retry-minutes:10}")
    private long slowRetryMinutes;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private Clock clock;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ------------------------------------------------------------------ keys

    /**
     * Charge key PAY-{paymentId}-{attempt}-{random}. Same attempt = same key, however many times it is retried.
     * The random part stops anyone from computing another user's key and using it at bank first (review finding 1).
     * Payments created before the random part existed keep their old key, so their retries stay idempotent.
     */
    public static String payKey(Payment payment) {
        String base = "PAY-" + payment.getId() + "-" + payment.currentAttempt();
        return payment.getPayKeyNonce() == null ? base : base + "-" + payment.getPayKeyNonce();
    }

    /** Refund key REFUND-{paymentId}-{random}. One refund per payment (review finding 2). */
    public static String refundKey(Payment payment) {
        String base = "REFUND-" + payment.getId();
        return payment.getRefundKeyNonce() == null ? base : base + "-" + payment.getRefundKeyNonce();
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 16 hex characters = 64 random bits: not guessable, short enough to read in a log */
    static String newKeyNonce() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    // ------------------------------------------------------------------ placing an order, Pay Now

    /**
     * Create the payment for a new order and its one PAY task, in the caller's transaction (place-order).
     * If the order already has a payment, return it and write nothing: a second PAY task would be a second charge.
     */
    @Transactional
    public Payment createPayment(Long orderId, BigDecimal amount) {
        Optional<Payment> existing = paymentRepository.findByOrderId(orderId);
        if (existing.isPresent()) {
            logger.info("Payment already exists for orderId={}, status={}", orderId, existing.get().getStatus());
            return existing.get();
        }

        Payment payment = new Payment();
        payment.setOrderId(orderId);
        payment.setAmount(amount);
        payment.setStatus(PaymentStatus.PENDING);
        payment.setAttempt(1);
        payment.setPayKeyNonce(newKeyNonce());
        Payment saved = paymentRepository.save(payment);

        outboxService.createPayTask(orderId, saved.getId(), payKey(saved));
        logger.info("Payment created: id={}, orderId={}, key={}", saved.getId(), orderId, payKey(saved));
        return saved;
    }

    public enum PayNowResult { PROCESSING, ALREADY_PAID }

    /**
     * Pay Now (rule 8). Lock the payment row, then:
     * - FAILED (bank definitely said no): back to PENDING, attempt + 1, new PAY task with the new key
     * - PENDING / PAY_UNKNOWN: change nothing. The money may already be moving; a new key could charge twice
     * - SUCCESS: already paid
     * The lock makes two tabs take turns: the second one waits, then sees PENDING and does nothing.
     */
    @Transactional
    public PayNowResult payNow(Long orderId) {
        Payment payment = paymentRepository.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new IllegalStateException("Payment not found for order " + orderId));

        switch (payment.getStatus()) {
            case FAILED -> {
                payment.setStatus(PaymentStatus.PENDING);
                payment.setAttempt(payment.currentAttempt() + 1);
                payment.setPayKeyNonce(newKeyNonce()); // new attempt, new key
                payment.setErrorMessage(null);
                paymentRepository.save(payment);
                outboxService.createPayTask(orderId, payment.getId(), payKey(payment));
                logger.info("Pay Now: new attempt for orderId={}, key={}", orderId, payKey(payment));
                return PayNowResult.PROCESSING;
            }
            case PENDING, PAY_UNKNOWN -> {
                logger.info("Pay Now: payment still in progress for orderId={} (status={}), nothing to do",
                        orderId, payment.getStatus());
                return PayNowResult.PROCESSING;
            }
            case SUCCESS -> {
                return PayNowResult.ALREADY_PAID;
            }
            default -> throw new IllegalStateException("Cannot pay an order whose payment is " + payment.getStatus());
        }
    }

    // ------------------------------------------------------------------ PAY task (called by OutboxProcessor)

    /** Everything OutboxProcessor needs to send one transfer */
    public record PayCall(Long taskId, Long orderId, String idempotencyKey, String fromAccount, String toAccount,
                          BigDecimal amount, boolean slowRetry) {}

    /**
     * Before each bank call: is this PAY task still worth sending? If yes, count the attempt and return what to send.
     * Counting happens in its own transaction before the call, so "retryCount = 0" really means "never sent".
     * Returns empty (and closes the task) when there is nothing to send.
     */
    @Transactional
    public Optional<PayCall> startPayAttempt(Long taskId) {
        PaymentOutbox task = outboxRepository.findById(taskId).orElse(null);
        if (task == null || !"PENDING".equals(task.getStatus())) {
            return Optional.empty();
        }
        Payment payment = paymentRepository.findByOrderIdForUpdate(task.getOrderId()).orElse(null);
        if (payment == null) {
            closeTask(task, "PROCESSED", "payment not found");
            return Optional.empty();
        }
        // Old tasks written before step 2 have no key: they belong to the payment's current attempt
        if (task.getIdempotencyKey() == null) {
            task.setIdempotencyKey(payKey(payment));
        }
        if (!CHARGE_OPEN.contains(payment.getStatus()) || !task.getIdempotencyKey().equals(payKey(payment))) {
            closeTask(task, "PROCESSED", "stale: payment is " + payment.getStatus() + ", current key " + payKey(payment));
            return Optional.empty();
        }

        Order order = orderRepository.findById(task.getOrderId()).orElse(null);
        // Cancelled before we ever sent anything: no money can have moved, so this is a definite failure
        if (task.getRetryCount() == 0 && payment.getStatus() == PaymentStatus.PENDING && isCancelled(order)) {
            handlePaymentFailure(payment, "Order cancelled before payment was sent");
            closeTask(task, "PROCESSED", "order cancelled before first attempt");
            return Optional.empty();
        }

        String fromAccount = order == null ? null : accountRepository.findById(order.getUserId())
                .map(acc -> acc.getBankAccountNumber())
                .filter(acc -> !acc.isEmpty())
                .orElse(null);
        if (fromAccount == null) {
            handlePaymentFailure(payment, "No linked bank account for user");
            closeTask(task, "PROCESSED", "no bank account");
            return Optional.empty();
        }

        task.setRetryCount(task.getRetryCount() + 1);
        outboxRepository.save(task);
        return Optional.of(new PayCall(taskId, task.getOrderId(), task.getIdempotencyKey(), fromAccount, storeAccount,
                payment.getAmount(), payment.getStatus() == PaymentStatus.PAY_UNKNOWN));
    }

    /**
     * Bank said the charge went through. Lock the order row, then the payment row, and look at the order (rule 1):
     * - still waiting for payment → PAID, write PAYMENT_SUCCESS (creates the delivery)
     * - already cancelled (by the user or the 15-minute timeout) → the money must go back: write a REFUND task
     */
    @Transactional
    public void onPaySucceeded(Long taskId, String bankTxnId) {
        PaymentOutbox task = outboxRepository.findById(taskId).orElseThrow();
        Order order = orderRepository.findByIdForUpdate(task.getOrderId()).orElseThrow();
        Payment payment = paymentRepository.findByOrderIdForUpdate(task.getOrderId()).orElseThrow();

        if (!CHARGE_OPEN.contains(payment.getStatus())) {
            closeTask(task, "PROCESSED", "duplicate success, payment already " + payment.getStatus());
            return;
        }
        payment.setBankTxnId(bankTxnId);
        payment.setPaymentDate(LocalDateTime.now(clock));
        payment.setErrorMessage(null);

        if (isCancelled(order)) {
            payment.setStatus(PaymentStatus.REFUND_PENDING);
            payment.setRefundKeyNonce(newKeyNonce());
            paymentRepository.save(payment);
            outboxService.createRefundTask(order.getId(), payment.getId(), refundKey(payment),
                    "Paid after order was cancelled");
            logger.warn("Charge succeeded after order {} was cancelled: refund task written, key={}",
                    order.getId(), refundKey(payment));
        } else {
            payment.setStatus(PaymentStatus.SUCCESS);
            paymentRepository.save(payment);
            order.setStatus(OrderStatus.PAID);
            orderRepository.save(order);
            outboxService.createPaymentSuccessEvent(order.getId(), bankTxnId);
            logger.info("Payment successful: orderId={}, key={}, bankTxnId={}", order.getId(), task.getIdempotencyKey(), bankTxnId);
        }
        closeTask(task, "PROCESSED", "charged");
    }

    /** Bank definitely said no (e.g. insufficient funds): FAILED. The order stays pending so the user can Pay Now. */
    @Transactional
    public void onPayRejected(Long taskId, String reason) {
        PaymentOutbox task = outboxRepository.findById(taskId).orElseThrow();
        Payment payment = paymentRepository.findByOrderIdForUpdate(task.getOrderId()).orElseThrow();
        if (CHARGE_OPEN.contains(payment.getStatus())) {
            handlePaymentFailure(payment, reason);
        }
        closeTask(task, "PROCESSED", "rejected: " + reason);
    }

    /**
     * No answer after the fast retries (or after one slow retry). Never FAILED, attempt unchanged (rule 7):
     * PAY_UNKNOWN + alert the first time, and try the same key again in 10 minutes.
     */
    @Transactional
    public void onPayStillUnknown(Long taskId, String lastError) {
        PaymentOutbox task = outboxRepository.findById(taskId).orElseThrow();
        Payment payment = paymentRepository.findByOrderIdForUpdate(task.getOrderId()).orElseThrow();
        if (payment.getStatus() == PaymentStatus.PENDING) {
            payment.setStatus(PaymentStatus.PAY_UNKNOWN);
            payment.setErrorMessage(lastError);
            paymentRepository.save(payment);
            logger.error("ALERT payment_unknown orderId={} paymentId={} key={} attempts={} lastError={}",
                    task.getOrderId(), payment.getId(), task.getIdempotencyKey(), task.getRetryCount(), lastError);
        } else {
            logger.warn("Payment still unknown: orderId={}, key={}, attempts={}",
                    task.getOrderId(), task.getIdempotencyKey(), task.getRetryCount());
        }
        task.setNextAttemptAt(LocalDateTime.now(clock).plusMinutes(slowRetryMinutes));
        outboxRepository.save(task);
    }

    // ------------------------------------------------------------------ refunds

    /**
     * Ask for the money back: write a REFUND task (rule 2). Used by cancel, lost parcel and compensation.
     * Never calls bank, so the caller's request does not wait for bank and a timeout cannot lose the refund.
     * Only a SUCCESS payment needs a refund. If the charge is still in progress, its result transaction will see
     * the cancelled order and write the refund itself (rule 1).
     */
    @Transactional
    public Payment requestRefund(Long orderId, String reason) {
        Payment payment = paymentRepository.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found for orderId: " + orderId));
        if (payment.getStatus() != PaymentStatus.SUCCESS) {
            logger.info("No refund task for orderId={}: payment is {}", orderId, payment.getStatus());
            return payment;
        }
        payment.setStatus(PaymentStatus.REFUND_PENDING);
        payment.setRefundKeyNonce(newKeyNonce());
        paymentRepository.save(payment);
        outboxService.createRefundTask(orderId, payment.getId(), refundKey(payment), reason);
        return payment;
    }

    /** Everything OutboxProcessor needs to send one refund */
    public record RefundCall(Long taskId, Long orderId, String idempotencyKey, String originalTransactionId,
                             String reason, boolean slowRetry) {}

    @Transactional
    public Optional<RefundCall> startRefundAttempt(Long taskId) {
        PaymentOutbox task = outboxRepository.findById(taskId).orElse(null);
        if (task == null || !"PENDING".equals(task.getStatus())) {
            return Optional.empty();
        }
        Payment payment = paymentRepository.findByOrderIdForUpdate(task.getOrderId()).orElse(null);
        if (payment == null || !REFUND_OPEN.contains(payment.getStatus())) {
            closeTask(task, "PROCESSED", "stale: payment is " + (payment == null ? "missing" : payment.getStatus()));
            return Optional.empty();
        }
        task.setRetryCount(task.getRetryCount() + 1);
        outboxRepository.save(task);
        return Optional.of(new RefundCall(taskId, task.getOrderId(), task.getIdempotencyKey(), payment.getBankTxnId(),
                payloadText(task, "reason"), payment.getStatus() == PaymentStatus.REFUND_UNKNOWN));
    }

    @Transactional
    public void onRefundSucceeded(Long taskId, String refundTxnId) {
        PaymentOutbox task = outboxRepository.findById(taskId).orElseThrow();
        Payment payment = paymentRepository.findByOrderIdForUpdate(task.getOrderId()).orElseThrow();
        if (REFUND_OPEN.contains(payment.getStatus())) {
            payment.setStatus(PaymentStatus.REFUNDED);
            payment.setErrorMessage(payloadText(task, "reason"));
            paymentRepository.save(payment);
            outboxService.createRefundSuccessEvent(task.getOrderId(), refundTxnId); // refund email
            logger.info("Refund successful: orderId={}, key={}, refundTxnId={}", task.getOrderId(), task.getIdempotencyKey(), refundTxnId);
        }
        closeTask(task, "PROCESSED", "refunded");
    }

    /** Bank refused the refund. That should never happen, so stop the task and alert a human. */
    @Transactional
    public void onRefundRejected(Long taskId, String reason) {
        PaymentOutbox task = outboxRepository.findById(taskId).orElseThrow();
        Payment payment = paymentRepository.findByOrderIdForUpdate(task.getOrderId()).orElseThrow();
        payment.setErrorMessage("Refund rejected: " + reason);
        paymentRepository.save(payment);
        closeTask(task, "FAILED", "refund rejected: " + reason);
        logger.error("ALERT refund_rejected orderId={} paymentId={} key={} reason={}",
                task.getOrderId(), payment.getId(), task.getIdempotencyKey(), reason);
    }

    @Transactional
    public void onRefundStillUnknown(Long taskId, String lastError) {
        PaymentOutbox task = outboxRepository.findById(taskId).orElseThrow();
        Payment payment = paymentRepository.findByOrderIdForUpdate(task.getOrderId()).orElseThrow();
        if (payment.getStatus() == PaymentStatus.REFUND_PENDING) {
            payment.setStatus(PaymentStatus.REFUND_UNKNOWN);
            payment.setErrorMessage(lastError);
            paymentRepository.save(payment);
            logger.error("ALERT refund_unknown orderId={} paymentId={} key={} attempts={} lastError={}",
                    task.getOrderId(), payment.getId(), task.getIdempotencyKey(), task.getRetryCount(), lastError);
        } else {
            logger.warn("Refund still unknown: orderId={}, key={}, attempts={}",
                    task.getOrderId(), task.getIdempotencyKey(), task.getRetryCount());
        }
        task.setNextAttemptAt(LocalDateTime.now(clock).plusMinutes(slowRetryMinutes));
        outboxRepository.save(task);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Bank definitely said no (or there is nothing to charge from): FAILED.
     * Only FAILED lets Pay Now try again with a new key (rule 8). The order is not touched.
     */
    @Transactional
    public void handlePaymentFailure(Payment payment, String errorMessage) {
        logger.warn("Payment failed: orderId={}, reason={}", payment.getOrderId(), errorMessage);
        payment.setStatus(PaymentStatus.FAILED);
        payment.setErrorMessage(errorMessage);
        paymentRepository.save(payment);
    }

    private void closeTask(PaymentOutbox task, String status, String why) {
        task.setStatus(status);
        task.setProcessedAt(LocalDateTime.now(clock));
        outboxRepository.save(task);
        logger.info("{} task {} closed as {}: {}", task.getEventType(), task.getId(), status, why);
    }

    private static boolean isCancelled(Order order) {
        return order != null
                && (order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.CANCELLED_SYSTEM);
    }

    private String payloadText(PaymentOutbox task, String field) {
        try {
            JsonNode node = objectMapper.readTree(task.getPayload());
            return node.hasNonNull(field) ? node.get(field).asText() : null;
        } catch (Exception e) {
            return null;
        }
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
