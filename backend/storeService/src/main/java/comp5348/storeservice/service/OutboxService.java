package comp5348.storeservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import comp5348.storeservice.model.OrderStatus;
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

    @Autowired
    private OrderService orderService;

    @Autowired
    private PaymentService paymentService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 创建待支付事件
     */
    @Transactional
    public void createPaymentPendingEvent(Long orderId, BigDecimal amount) {
        logger.info("Creating PAYMENT_PENDING event for orderId={}", orderId);

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("orderId", orderId);
            payload.put("amount", amount.toString());

            PaymentOutbox outbox = new PaymentOutbox();
            outbox.setOrderId(orderId);
            outbox.setEventType("PAYMENT_PENDING");
            outbox.setPayload(objectMapper.writeValueAsString(payload));
            outbox.setStatus("PENDING");
            outbox.setRetryCount(0);

            outboxRepository.save(outbox);
            logger.info("PAYMENT_PENDING event created for orderId={}", orderId);

        } catch (Exception e) {
            logger.error("Failed to create PAYMENT_PENDING event: {}", e.getMessage(), e);
        }
    }

    /**
     * 创建支付成功事件
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
            outbox.setEventType("PAYMENT_SUCCESS");
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
     * 创建支付失败事件
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
            outbox.setEventType("PAYMENT_FAILED");
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
     * 创建退款成功事件
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
            outbox.setEventType("REFUND_SUCCESS");
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
        outboxMessage.setEventType(PaymentStatus.DELIVERY_FAILED.name()); // 使用 Enum

        // 构造 payload
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", orderId);
        payload.put("reason", reason);

        outboxMessage.setPayload(objectMapper.writeValueAsString(payload));

        outboxRepository.save(outboxMessage);
    }

    private boolean processDeliveryFailed(PaymentOutbox outbox) {
        try {
            Long orderId = outbox.getOrderId();
            Map<String, Object> payload = objectMapper.readValue(outbox.getPayload(), new TypeReference<>() {});
            String reason = (String) payload.getOrDefault("reason", "Delivery creation failed.");

            logger.warn("Processing DELIVERY_FAILED compensation for orderId: {}. Reason: {}", orderId, reason);

            // 1. 调用 PaymentService 的退款方法
            paymentService.refundPayment(orderId, "Auto-refund: " + reason);

            // 2. 将订单状态更新为一个特殊的失败状态 (可选，但推荐)
            // 你可能需要在 OrderStatus 中添加一个 CANCELLED_SYSTEM 状态
            orderService.updateOrderStatus(orderId, OrderStatus.CANCELLED);

            logger.info("Compensation (refund and cancellation) completed for orderId: {}", orderId);

            return true; // 补偿流程成功

        } catch (Exception e) {
            logger.error("FATAL: Compensation (refund) for DELIVERY_FAILED event failed for orderId: {}. Needs manual intervention.",
                    outbox.getOrderId(), e);
            return false; // 让这个补偿操作也进行重试
        }
    }
}


