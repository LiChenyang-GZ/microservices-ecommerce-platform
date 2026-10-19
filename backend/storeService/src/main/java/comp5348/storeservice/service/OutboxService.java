package comp5348.storeservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import comp5348.storeservice.model.PaymentOutbox;
import comp5348.storeservice.model.PaymentStatus;
import comp5348.storeservice.repository.PaymentOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Service
public class OutboxService {

    private static final Logger logger = LoggerFactory.getLogger(OutboxService.class);

    @Autowired
    private PaymentOutboxRepository outboxRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Money task types. Only OutboxProcessor executes them, and only it calls bank (rule 1). */
    public static final String TASK_PAY = "PAY";
    public static final String TASK_REFUND = "REFUND";

    /**
     * Write a PAY task: "charge this payment with this key". Joins the caller's transaction,
     * so the task exists exactly when the payment change that needs it is committed (rule 2).
     * Not wrapped in try/catch: if the task cannot be written, the caller's transaction must roll back.
     */
    public PaymentOutbox createPayTask(Long orderId, Long paymentId, String idempotencyKey) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", orderId);
        payload.put("paymentId", paymentId);
        return saveTask(orderId, TASK_PAY, idempotencyKey, payload);
    }

    /** Write a REFUND task: "refund this payment with key REFUND-{paymentId}". Same rules as createPayTask. */
    public PaymentOutbox createRefundTask(Long orderId, Long paymentId, String idempotencyKey, String reason) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", orderId);
        payload.put("paymentId", paymentId);
        payload.put("reason", reason);
        return saveTask(orderId, TASK_REFUND, idempotencyKey, payload);
    }

    private PaymentOutbox saveTask(Long orderId, String type, String idempotencyKey, Map<String, Object> payload) {
        PaymentOutbox task = new PaymentOutbox();
        task.setOrderId(orderId);
        task.setEventType(type);
        task.setIdempotencyKey(idempotencyKey);
        try {
            task.setPayload(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise " + type + " task payload", e);
        }
        task.setStatus("PENDING");
        task.setRetryCount(0);
        PaymentOutbox saved = outboxRepository.save(task);
        logger.info("{} task written: taskId={}, orderId={}, key={}", type, saved.getId(), orderId, idempotencyKey);
        return saved;
    }

    /**
     * Create payment success event
     */
    @Transactional
    public void createPaymentSuccessEvent(Long orderId, String bankTxnId) {
        logger.info("Creating PAYMENT_SUCCESS event for orderId={}", orderId);

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("orderId", orderId);
            payload.put("bankTxnId", bankTxnId);

            PaymentOutbox outbox = new PaymentOutbox();
            outbox.setOrderId(orderId);
            // Consistently use PaymentStatus enum name
            outbox.setEventType(PaymentStatus.SUCCESS.name());
            outbox.setPayload(objectMapper.writeValueAsString(payload));
            outbox.setStatus("PENDING");
            outbox.setRetryCount(0);

            outboxRepository.save(outbox);
            logger.info("PAYMENT_SUCCESS event created for orderId={}", orderId);

        } catch (Exception e) {
            logger.error("Failed to create PAYMENT_SUCCESS event: {}", e.getMessage(), e);
        }
    }

    /**
     * Create payment failed event
     */
    @Transactional
    public void createPaymentFailedEvent(Long orderId, String error) {
        logger.info("Creating PAYMENT_FAILED event for orderId={}", orderId);

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("orderId", orderId);
            payload.put("error", error);

            PaymentOutbox outbox = new PaymentOutbox();
            outbox.setOrderId(orderId);
            // Consistently use PaymentStatus enum name
            outbox.setEventType(PaymentStatus.FAILED.name());
            outbox.setPayload(objectMapper.writeValueAsString(payload));
            outbox.setStatus("PENDING");
            outbox.setRetryCount(0);

            outboxRepository.save(outbox);
            logger.info("PAYMENT_FAILED event created for orderId={}", orderId);

        } catch (Exception e) {
            logger.error("Failed to create PAYMENT_FAILED event: {}", e.getMessage(), e);
        }
    }

    /**
     * Create refund success event
     */
    @Transactional
    public void createRefundSuccessEvent(Long orderId, String refundTxnId) {
        logger.info("Creating REFUND_SUCCESS event for orderId={}", orderId);

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("orderId", orderId);
            payload.put("refundTxnId", refundTxnId);

            PaymentOutbox outbox = new PaymentOutbox();
            outbox.setOrderId(orderId);
            // Consistently use PaymentStatus enum name (not REFUND_SUCCESS string)
            outbox.setEventType(PaymentStatus.REFUNDED.name());
            outbox.setPayload(objectMapper.writeValueAsString(payload));
            outbox.setStatus("PENDING");
            outbox.setRetryCount(0);

            outboxRepository.save(outbox);
            logger.info("REFUND_SUCCESS event created for orderId={}", orderId);

        } catch (Exception e) {
            logger.error("Failed to create REFUND_SUCCESS event: {}", e.getMessage(), e);
        }
    }

    @Transactional
    public void createDeliveryFailedEvent(Long orderId, String reason) throws JsonProcessingException {
        logger.info("Creating DELIVERY_FAILED event for orderId: {}", orderId);

        PaymentOutbox outboxMessage = new PaymentOutbox();
        outboxMessage.setOrderId(orderId);
        outboxMessage.setEventType(PaymentStatus.DELIVERY_FAILED.name()); // Use Enum

        // Construct payload
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", orderId);
        payload.put("reason", reason);

        outboxMessage.setPayload(objectMapper.writeValueAsString(payload));

        outboxRepository.save(outboxMessage);
    }
}


