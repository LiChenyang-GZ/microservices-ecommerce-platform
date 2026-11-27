package comp5348.storeservice.scheduler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import comp5348.storeservice.adapter.BankAdapter;
import comp5348.storeservice.adapter.BankCallResult;
import comp5348.storeservice.dto.BankRefundRequest;
import comp5348.storeservice.dto.BankTransferRequest;
import comp5348.storeservice.model.*;
import comp5348.storeservice.repository.AccountRepository;
import comp5348.storeservice.repository.OrderRepository;
import comp5348.storeservice.repository.PaymentOutboxRepository;
import comp5348.storeservice.service.OrderService;
import comp5348.storeservice.service.OutboxService;
import comp5348.storeservice.service.PaymentService;
import comp5348.storeservice.service.WarehouseService;
import comp5348.storeservice.dto.UnholdProductRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Component
@EnableScheduling
public class OutboxProcessor {
    
    private static final Logger logger = LoggerFactory.getLogger(OutboxProcessor.class);
    
    @Autowired
    private PaymentOutboxRepository outboxRepository;
    
    @Autowired
    private PaymentService paymentService;
    
    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private comp5348.storeservice.adapter.EmailAdapter emailAdapter;

    @Autowired
    private WarehouseService warehouseService;

    @Autowired
    private BankAdapter bankAdapter;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /** For a non-money task's retry count: always its own transaction, never the one the work rolled back */
    private TransactionTemplate requiresNew;

    @Autowired
    void setTransactionManager(PlatformTransactionManager transactionManager) {
        requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Autowired
    private Clock clock;

    // Rule 7: on no answer, retry the same key quickly up to this many times, then mark it unknown
    @Value("${payment.bank.fast-retries:5}")
    private int fastRetries;

    @Value("${payment.bank.fast-retry-backoff-ms:500}")
    private long fastRetryBackoffMs;
    
    @Value("${outbox.processor.max-retries:3}")
    private int maxRetries;
    
    @Value("${outbox.processor.enabled:true}")
    private boolean enabled;
    
    @Value("${store.service.url:http://localhost:8082}")
    private String storeServiceUrl;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate;

    public OutboxProcessor(RestTemplateBuilder restTemplateBuilder) {
        this.restTemplate = restTemplateBuilder
                .requestFactory(() -> {
                    org.springframework.http.client.SimpleClientHttpRequestFactory factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
                    factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
                    factory.setReadTimeout((int) Duration.ofSeconds(5).toMillis());
                    return factory;
                })
                .build();
    }

    /**
     * Every 5 seconds: run every pending task, oldest first.
     *
     * Deliberately NOT one big @Transactional. Each step below opens its own short transaction, so no database
     * lock is held while we wait for bank. (Otherwise a user cancelling during a slow charge would wait for bank,
     * and the "charge succeeded but the order was cancelled meanwhile" check could read a stale order.)
     */
    @Scheduled(fixedDelay = 5000)
    public void processOutbox() {
        if (!enabled) {
            return;
        }

        List<PaymentOutbox> pending = outboxRepository.findByStatusOrderByIdAsc("PENDING");
        if (pending.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        logger.info("Processing {} pending outbox tasks", pending.size());

        for (PaymentOutbox task : pending) {
            if (task.getNextAttemptAt() != null && task.getNextAttemptAt().isAfter(now)) {
                continue; // a slow retry that is not due yet
            }
            try {
                switch (task.getEventType()) {
                    // "PENDING" / "PAYMENT_PENDING": PAY tasks written before step 2
                    case OutboxService.TASK_PAY, "PENDING", "PAYMENT_PENDING" -> runPayTask(task.getId());
                    case OutboxService.TASK_REFUND -> runRefundTask(task.getId());
                    default -> runOtherTask(task);
                }
            } catch (Exception e) {
                // A database problem in one task must not stop the others. The task stays PENDING and is retried
                logger.error("Error processing outbox task id={}: {}", task.getId(), e.getMessage(), e);
            }
        }
    }

    /**
     * One PAY task: call bank with the task's key, at most fastRetries times in a row while there is no answer.
     * A task that is already PAY_UNKNOWN gets one try per slow round (every 10 minutes).
     */
    private void runPayTask(Long taskId) {
        String lastError = null;
        for (int i = 0; i < fastRetries; i++) {
            Optional<PaymentService.PayCall> next = paymentService.startPayAttempt(taskId);
            if (next.isEmpty()) {
                return; // nothing (left) to send
            }
            PaymentService.PayCall call = next.get();
            BankCallResult result = bankAdapter.transfer(new BankTransferRequest(
                    call.idempotencyKey(), call.fromAccount(), call.toAccount(), call.amount()));
            switch (result.outcome()) {
                case SUCCEEDED -> {
                    paymentService.onPaySucceeded(taskId, result.transactionId());
                    return;
                }
                case REJECTED -> {
                    paymentService.onPayRejected(taskId, result.message());
                    return;
                }
                case KEY_CONFLICT -> {
                    // Sending it again now gets the same answer: hold it and look again in 10 minutes
                    paymentService.onPayKeyConflict(taskId, result.message());
                    return;
                }
                case UNKNOWN -> lastError = result.message();
            }
            if (shuttingDown(taskId, call.idempotencyKey())) {
                return;
            }
            if (call.slowRetry()) {
                break;
            }
            pause();
        }
        paymentService.onPayStillUnknown(taskId, lastError);
    }

    /** One REFUND task. Same shape as runPayTask; key REFUND-{paymentId}-{random}. */
    private void runRefundTask(Long taskId) {
        String lastError = null;
        for (int i = 0; i < fastRetries; i++) {
            Optional<PaymentService.RefundCall> next = paymentService.startRefundAttempt(taskId);
            if (next.isEmpty()) {
                return;
            }
            PaymentService.RefundCall call = next.get();
            BankCallResult result = bankAdapter.refund(new BankRefundRequest(
                    call.idempotencyKey(), call.originalTransactionId(), call.reason()));
            switch (result.outcome()) {
                case SUCCEEDED -> {
                    paymentService.onRefundSucceeded(taskId, result.transactionId());
                    return;
                }
                case REJECTED -> {
                    paymentService.onRefundRejected(taskId, result.message());
                    return;
                }
                case KEY_CONFLICT -> {
                    paymentService.onRefundKeyConflict(taskId, result.message());
                    return;
                }
                case UNKNOWN -> lastError = result.message();
            }
            if (shuttingDown(taskId, call.idempotencyKey())) {
                return;
            }
            if (call.slowRetry()) {
                break;
            }
            pause();
        }
        paymentService.onRefundStillUnknown(taskId, lastError);
    }

    /**
     * Store is shutting down: Spring interrupts this thread. The gRPC blocking stub answers an interrupt with
     * CANCELLED (unknown), and with the flag still set every next call and pause() return at once, so without this
     * check the 5 fast retries burn in milliseconds → *_UNKNOWN, a false ALERT, and a 10-minute wait after restart.
     * An interrupt says nothing about bank, so stop here without marking anything: the task stays PENDING with the
     * same key and the first round after restart sends it again (rule 7: same key is always safe).
     */
    private boolean shuttingDown(Long taskId, String key) {
        if (!Thread.currentThread().isInterrupted()) {
            return false;
        }
        logger.warn("Outbox thread interrupted (shutdown?): leaving task id={} key={} pending for the next start",
                taskId, key);
        return true;
    }

    private void pause() {
        try {
            Thread.sleep(fastRetryBackoffMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * One non-money task (refund emails; old FAILED tasks that give stock back). The work runs in one transaction.
     * When it fails (throws, gives up, or cannot commit because something inside marked it rollback-only), the
     * retry count is written in a transaction of its own (review 4b #7). It used to be counted inside the work's
     * transaction and rolled back with it: the task never reached maxRetries and logged an ERROR every 5 seconds.
     */
    private void runOtherTask(PaymentOutbox task) {
        if (task.getRetryCount() >= maxRetries) {
            return;
        }
        String error;
        try {
            Boolean done = transactionTemplate.execute(status -> outboxRepository.findById(task.getId())
                    .map(this::processOutboxMessage).orElse(true));
            if (Boolean.TRUE.equals(done)) {
                return;
            }
            error = "gave up (see the log above)";
        } catch (RuntimeException e) {
            // Rolled back on commit: the real error was caught inside and only logged above (review 88 🟡6)
            error = e instanceof UnexpectedRollbackException
                    ? "rolled back by an error caught inside the task (see the log lines before this)"
                    : oneLine(NestedExceptionUtils.getMostSpecificCause(e).getMessage());
            logger.error("Outbox task id={} ({}) failed: {}", task.getId(), task.getEventType(), error, e);
        }
        recordFailure(task.getId(), error);
    }

    /** +1 on the task's retry count in a new transaction; at maxRetries the task is FAILED and alerts once */
    private void recordFailure(Long taskId, String error) {
        requiresNew.executeWithoutResult(status -> outboxRepository.findById(taskId).ifPresent(task -> {
            if (!"PENDING".equals(task.getStatus())) {
                // The work did commit after all (the commit's answer was lost), or another instance finished it
                // meanwhile: nothing to count, and no false ALERT (review 88 🟡2)
                logger.info("Outbox task id={} is {} now, not counting this failure", taskId, task.getStatus());
                return;
            }
            task.setRetryCount(task.getRetryCount() + 1);
            if (task.getRetryCount() >= maxRetries) {
                task.setStatus("FAILED");
                boolean stockTask = "FAILED".equals(task.getEventType()) || "PAYMENT_FAILED".equals(task.getEventType());
                logger.error("ALERT outbox_task_failed taskId={} type={} orderId={} retries={} lastError={}{}",
                        task.getId(), task.getEventType(), task.getOrderId(), task.getRetryCount(), error,
                        stockTask ? " (its stock was not given back: check this order's HOLD rows by hand)" : "");
            } else {
                logger.warn("Outbox task id={} failed, will retry: retryCount={}", task.getId(), task.getRetryCount());
            }
            outboxRepository.save(task);
        }));
    }

    private static String oneLine(String message) {
        return message == null ? null : message.replaceAll("\\s*\\R\\s*", " ");
    }

    /**
     * Process one non-money event (emails; legacy delivery tasks are closed). Runs inside runOtherTask's
     * transaction. Returns true when done; a failure is thrown (or false for an unknown type) and counted there.
     */
    private boolean processOutboxMessage(PaymentOutbox outbox) {
        logger.info("Processing outbox message: id={}, eventType={}, orderId={}",
                outbox.getId(), outbox.getEventType(), outbox.getOrderId());

        // Compatible with historical/async message event names: e.g., PAYMENT_SUCCESS/REFUND_SUCCESS
        String rawEventType = outbox.getEventType();
        String normalized;
        if (rawEventType == null) {
            throw new IllegalArgumentException("Outbox eventType is null");
        }
        switch (rawEventType) {
            case "PAYMENT_SUCCESS":
                normalized = "SUCCESS"; break;
            case "PAYMENT_FAILED":
                normalized = "FAILED"; break;
            case "REFUND_SUCCESS":
                normalized = "REFUNDED"; break;
            default:
                // Remove unified prefix for robustness
                normalized = rawEventType.replaceFirst("^PAYMENT_", "");
        }

        PaymentStatus eventType = PaymentStatus.valueOf(normalized); // Convert string to Enum
        switch (eventType) {
            case SUCCESS:
            case DELIVERY_FAILED:
                // Written before wave 2 step 4b to create the delivery over HTTP / to refund 30 s after that
                // failed. Delivery now comes from OrderPaid on Kafka, so there is nothing to do (E4: check for
                // leftovers by hand before upgrading). Close it so it does not sit here forever.
                logger.warn("Closing legacy {} task id={} for orderId={}: deliveries come from OrderPaid now",
                        eventType, outbox.getId(), outbox.getOrderId());
                break;

            case FAILED:
                processPaymentFailed(outbox);
                break;

            case REFUNDED:
                processRefundSuccess(outbox);
                break;

            default:
                logger.warn("Unknown event type: {}", outbox.getEventType());
                return false;
        }

        outbox.setStatus("PROCESSED");
        outbox.setProcessedAt(LocalDateTime.now(clock));
        outboxRepository.save(outbox);
        logger.info("Outbox message processed successfully: id={}", outbox.getId());
        return true;
    }

    private Map<String, Object> payloadOf(PaymentOutbox outbox) {
        try {
            return objectMapper.readValue(outbox.getPayload(), new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unreadable payload of outbox task " + outbox.getId(), e);
        }
    }

    /**
     * Process payment failed event - release reserved inventory + send email notification.
     * Only tasks written before 7b654a5 (2025-12-09) are left: nothing writes FAILED tasks any more; today the
     * stock of a failed payment comes back through UnpaidOrderCanceller. A failure to release is thrown, not
     * swallowed: the whole task rolls back and runOtherTask counts it.
     */
    private void processPaymentFailed(PaymentOutbox outbox) {
        Map<String, Object> payload = payloadOf(outbox);
        Long orderId = Long.valueOf(payload.get("orderId").toString());
        String error = String.valueOf(payload.get("error"));

        logger.info("Payment failed for orderId={}, error={}", orderId, error);

        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            logger.warn("Order not found for failed payment, cannot release stock. orderId={}", orderId);
            return; // Order doesn't exist, no need to release inventory, consider processing successful
        }

        // 1. Release inventory reservation
        if (order.getInventoryTransactionIds() != null && !order.getInventoryTransactionIds().isEmpty()) {
            List<Long> txIds = Arrays.stream(order.getInventoryTransactionIds().split(","))
                    .filter(s -> s != null && !s.trim().isEmpty())
                    .map(Long::valueOf)
                    .collect(Collectors.toList());

            if (!txIds.isEmpty()) {
                UnholdProductRequest unholdRequest = new UnholdProductRequest();
                unholdRequest.setInventoryTransactionIds(txIds);
                warehouseService.unholdProduct(unholdRequest);
                logger.info("Released inventory for failed payment, orderId={}", orderId);
            }
        }

        // 2. Send failure notification email
        try {
            accountRepository.findById(order.getUserId()).ifPresent(acc -> {
                if (acc.getEmail() != null) {
                    emailAdapter.sendOrderFailed(acc.getEmail(), String.valueOf(orderId), error);
                }
            });
        } catch (Exception e) {
            logger.warn("Failed to send order failure email for orderId={}: {}", orderId, e.getMessage());
            // Email sending failure doesn't affect main flow
        }
    }

    /**
     * Process refund success event - send email notification. A payload it cannot read is thrown and counted.
     */
    private void processRefundSuccess(PaymentOutbox outbox) {
        Map<String, Object> payload = payloadOf(outbox);
        Long orderId = Long.valueOf(payload.get("orderId").toString());
        String refundTxnId = payload.get("refundTxnId").toString();

        logger.info("Refund success for orderId={}, refundTxnId={}", orderId, refundTxnId);

        try {
            Order order = orderRepository.findById(orderId).orElse(null);
            String email = null;
            if (order != null) {
                var acc = accountRepository.findById(order.getUserId()).orElse(null);
                if (acc != null) email = acc.getEmail();
            }
            if (email != null) {
                emailAdapter.sendRefundSuccess(email, String.valueOf(orderId), refundTxnId);
            }
        } catch (Exception e) {
            logger.warn("Send refund success email error: {}", e.getMessage());
        }
    }
}
