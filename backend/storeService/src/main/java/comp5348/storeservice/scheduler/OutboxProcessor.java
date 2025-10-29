package comp5348.storeservice.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import comp5348.storeservice.adapter.DeliveryAdapter;
import comp5348.storeservice.dto.DeliveryRequestDTO;
import comp5348.storeservice.dto.DeliveryResponseDTO;
import comp5348.storeservice.model.Account;
import comp5348.storeservice.model.Order;
import comp5348.storeservice.model.OrderItem;
import comp5348.storeservice.model.PaymentOutbox;
import comp5348.storeservice.repository.AccountRepository;
import comp5348.storeservice.repository.OrderRepository;
import comp5348.storeservice.repository.PaymentOutboxRepository;
import comp5348.storeservice.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@EnableScheduling
public class OutboxProcessor {
    
    private static final Logger logger = LoggerFactory.getLogger(OutboxProcessor.class);
    
    @Autowired
    private PaymentOutboxRepository outboxRepository;
    
    @Autowired
    private PaymentService paymentService;
    
    @Autowired
    private DeliveryAdapter deliveryAdapter;
    
    @Autowired
    private OrderRepository orderRepository;
    
    @Autowired
    private AccountRepository accountRepository;
    
    @Value("${outbox.processor.max-retries:3}")
    private int maxRetries;
    
    @Value("${outbox.processor.enabled:true}")
    private boolean enabled;
    
    @Value("${delivery.notification.url:http://localhost:8082/api/delivery-webhook}")
    private String deliveryNotificationUrl;
    
    @Value("${store.service.url:http://localhost:8082}")
    private String storeServiceUrl;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate;
    
    @SuppressWarnings("deprecation")
    public OutboxProcessor(RestTemplateBuilder restTemplateBuilder) {
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(5))
                .build();
    }
    
    /**
     * 定时处理Outbox消息 - 每5秒执行一次
     */
    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void processOutbox() {
        if (!enabled) {
            return;
        }
        
        try {
            // 查询待处理的Outbox记录（PENDING状态且重试次数小于最大值）
            List<PaymentOutbox> pendingOutboxes = outboxRepository.findByStatusAndRetryCountLessThan("PENDING", maxRetries);
            
            if (pendingOutboxes.isEmpty()) {
                return;
            }
            
            logger.info("Processing {} pending outbox messages", pendingOutboxes.size());
            
            for (PaymentOutbox outbox : pendingOutboxes) {
                processOutboxMessage(outbox);
            }
            
        } catch (Exception e) {
            logger.error("Error in outbox processor: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 处理单个Outbox消息
     */
    private void processOutboxMessage(PaymentOutbox outbox) {
        logger.info("Processing outbox message: id={}, eventType={}, orderId={}", 
                outbox.getId(), outbox.getEventType(), outbox.getOrderId());
        
        try {
            boolean success = false;
            
            switch (outbox.getEventType()) {
                case "PAYMENT_PENDING":
                    success = processPaymentPending(outbox);
                    break;
                    
                case "PAYMENT_SUCCESS":
                    success = processPaymentSuccess(outbox);
                    break;
                    
                case "PAYMENT_FAILED":
                    success = processPaymentFailed(outbox);
                    break;
                    
                case "REFUND_SUCCESS":
                    success = processRefundSuccess(outbox);
                    break;
                    
                default:
                    logger.warn("Unknown event type: {}", outbox.getEventType());
                    success = false;
            }
            
            if (success) {
                outbox.setStatus("PROCESSED");
                outbox.setProcessedAt(LocalDateTime.now());
                logger.info("Outbox message processed successfully: id={}", outbox.getId());
            } else {
                outbox.setRetryCount(outbox.getRetryCount() + 1);
                
                if (outbox.getRetryCount() >= maxRetries) {
                    outbox.setStatus("FAILED");
                    logger.error("Outbox message failed after {} retries: id={}", maxRetries, outbox.getId());
                } else {
                    logger.warn("Outbox message processing failed, will retry: id={}, retryCount={}", 
                            outbox.getId(), outbox.getRetryCount());
                }
            }
            
            outboxRepository.save(outbox);
            
        } catch (Exception e) {
            logger.error("Error processing outbox message: id={}, error={}", outbox.getId(), e.getMessage(), e);
            
            outbox.setRetryCount(outbox.getRetryCount() + 1);
            
            if (outbox.getRetryCount() >= maxRetries) {
                outbox.setStatus("FAILED");
            }
            
            outboxRepository.save(outbox);
        }
    }
    
    /**
     * 处理待支付事件 - 调用PaymentService处理支付
     */
    private boolean processPaymentPending(PaymentOutbox outbox) {
        try {
            Map<String, Object> payload = objectMapper.readValue(
                    outbox.getPayload(), new TypeReference<Map<String, Object>>() {});
            Long orderId = Long.valueOf(payload.get("orderId").toString());
            
            logger.info("Processing payment for orderId={}", orderId);
            paymentService.processPayment(orderId);
            
            return true;
            
        } catch (Exception e) {
            logger.error("Failed to process PAYMENT_PENDING: {}", e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 处理支付成功事件 - 创建配送请求（调用组员D的DeliveryService）
     */
    private boolean processPaymentSuccess(PaymentOutbox outbox) {
        try {
            Map<String, Object> payload = objectMapper.readValue(
                    outbox.getPayload(), new TypeReference<Map<String, Object>>() {});
            Long orderId = Long.valueOf(payload.get("orderId").toString());
            String bankTxnId = payload.get("bankTxnId").toString();
            
            logger.info("Payment success, triggering delivery request for orderId={}, bankTxnId={}", 
                    orderId, bankTxnId);
            
            // 1. 查询订单信息
            Optional<Order> orderOpt = orderRepository.findById(orderId);
            if (!orderOpt.isPresent()) {
                logger.error("Order not found: orderId={}", orderId);
                return false;
            }
            Order order = orderOpt.get();
            
            // 2. 查询用户信息
            Optional<Account> accountOpt = accountRepository.findById(order.getUserId());
            String email = "customer@example.com"; // 默认值
            String userName = "Customer"; // 默认值
            
            if (accountOpt.isPresent()) {
                Account account = accountOpt.get();
                email = account.getEmail();
                userName = account.getFirstName() + " " + account.getLastName();
            } else {
                logger.warn("Account not found for userId={}, using placeholder values", order.getUserId());
            }
            
            // 3. 获取订单商品信息（取第一个商品，如果有多个商品可以扩展）
            String productName = "Product";
            int quantity = 1;
            
            if (!order.getOrderItems().isEmpty()) {
                OrderItem firstItem = order.getOrderItems().get(0);
                productName = firstItem.getProduct().getName();
                quantity = firstItem.getQty();
                
                // 如果有多个商品，记录日志
                if (order.getOrderItems().size() > 1) {
                    logger.info("Order has {} items, using first item for delivery: {}", 
                            order.getOrderItems().size(), productName);
                }
            }
            
            // 4. 构造配送请求
            DeliveryRequestDTO deliveryRequest = new DeliveryRequestDTO();
            deliveryRequest.setOrderId(orderId.toString());
            deliveryRequest.setEmail(email);
            deliveryRequest.setUserName(userName);
            deliveryRequest.setToAddress("Default Address - 123 Main St"); // 占位值，等组员B实现地址管理
            
            // fromAddress 暂时写死，等组员B实现仓库功能
            List<String> fromAddresses = new ArrayList<>();
            fromAddresses.add("Warehouse-1, 456 Storage Rd");
            deliveryRequest.setFromAddress(fromAddresses);
            
            deliveryRequest.setProductName(productName);
            deliveryRequest.setQuantity(quantity);
            deliveryRequest.setNotificationUrl(deliveryNotificationUrl);
            
            // 5. 确认库存预留（调用组员B的API）
            try {
                String confirmUrl = storeServiceUrl + "/api/reservations/order/" + orderId + "/confirm";
                restTemplate.put(confirmUrl, null);
                logger.info("Confirmed reservations for successful payment, orderId={}", orderId);
            } catch (Exception e) {
                logger.error("Failed to confirm reservations for orderId={}: {}", orderId, e.getMessage());
                // 继续处理，不因确认失败而中断配送流程
            }
            
            // 6. 调用DeliveryService
            DeliveryResponseDTO response = deliveryAdapter.createDelivery(deliveryRequest);
            
            if (response.isSuccess()) {
                logger.info("Delivery request created successfully: orderId={}, deliveryId={}", 
                        orderId, response.getDeliveryId());
                return true;
            } else {
                logger.error("Delivery request failed: orderId={}, message={}", 
                        orderId, response.getMessage());
                return false;
            }
            
        } catch (Exception e) {
            logger.error("Failed to process PAYMENT_SUCCESS: {}", e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 处理支付失败事件 - 释放预留库存 + 发送Email通知
     */
    private boolean processPaymentFailed(PaymentOutbox outbox) {
        try {
            Map<String, Object> payload = objectMapper.readValue(
                    outbox.getPayload(), new TypeReference<Map<String, Object>>() {});
            Long orderId = Long.valueOf(payload.get("orderId").toString());
            String error = payload.get("error").toString();
            
            logger.info("Payment failed for orderId={}, error={}", orderId, error);
            
            // 1. 释放库存预留（调用组员B提供的API）✅
            boolean reservationReleased = false;
            try {
                String releaseUrl = storeServiceUrl + "/api/reservations/order/" + orderId;
                restTemplate.delete(releaseUrl);
                logger.info("Released reservations for failed payment, orderId={}", orderId);
                reservationReleased = true;
            } catch (Exception e) {
                logger.error("Failed to release reservations for orderId={}: {}", orderId, e.getMessage());
                // 预留释放失败，应该重试
                return false;
            }
            
            // 2. 发送失败通知邮件（等待组员D提供接口）
            // TODO: 等待组员D提供订单失败通知接口
            // 接口格式: POST /api/email/order-failure-notification
            // emailOutboxService.createFailureNotification(orderId, error);
            logger.warn("Email failure notification not implemented - waiting for Member D's interface");
            
            // 预留已释放，即使邮件未发送也认为处理成功（邮件可以后续补发）
            logger.info("Payment failure processed for orderId={}, reservation released={}", 
                    orderId, reservationReleased);
            return reservationReleased;
            
        } catch (Exception e) {
            logger.error("Failed to process PAYMENT_FAILED: {}", e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 处理退款成功事件 - 发送Email通知
     * 等待组员D提供接口后集成
     */
    private boolean processRefundSuccess(PaymentOutbox outbox) {
        try {
            Map<String, Object> payload = objectMapper.readValue(
                    outbox.getPayload(), new TypeReference<Map<String, Object>>() {});
            Long orderId = Long.valueOf(payload.get("orderId").toString());
            String refundTxnId = payload.get("refundTxnId").toString();
            
            logger.info("Refund success for orderId={}, refundTxnId={}", orderId, refundTxnId);
            
            // TODO: 等待组员D提供退款通知接口
            // 接口格式: POST /api/email/refund-notification
            // emailOutboxService.createRefundNotification(orderId, refundTxnId);
            logger.warn("Email refund notification not implemented - waiting for Member D's interface");
            
            // 暂时返回true以避免无限重试，实际应该在接口就绪后改为实际调用结果
            logger.info("Refund success event logged for orderId={}, awaiting integration", orderId);
            return true;
            
        } catch (Exception e) {
            logger.error("Failed to process REFUND_SUCCESS: {}", e.getMessage(), e);
            return false;
        }
    }
}


