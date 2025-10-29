package comp5348.storeservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import comp5348.storeservice.model.PaymentOutbox;
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
}


