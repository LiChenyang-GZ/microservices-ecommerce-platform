package comp5348.storeservice.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import comp5348.storeservice.adapter.DeliveryAdapter;
import comp5348.storeservice.dto.DeliveryRequestDTO;
import comp5348.storeservice.dto.DeliveryResponseDTO;
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
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

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
    private DeliveryAdapter deliveryAdapter;
    
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

            PaymentStatus eventType = PaymentStatus.valueOf(outbox.getEventType()); // 将字符串转换为Enum
            switch (eventType) {
                case PENDING:
                    success = processPaymentPending(outbox);
                    break;

                case SUCCESS:
                    success = processPaymentSuccess(outbox);
                    break;

                case FAILED:
                    success = processPaymentFailed(outbox);
                    break;

                case REFUNDED: // 假设 PaymentStatus 里有 REFUNDED
                    success = processRefundSuccess(outbox);
                    break;

                case DELIVERY_FAILED: // 假设 PaymentStatus/EventType 枚举中有这个
                    success = processDeliveryFailed(outbox);
                    break;

                default:
                    logger.warn("Unknown event type: {}", outbox.getEventType());
                    success = false; // 或者直接抛出异常
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
            Map<String, Object> payload = objectMapper.readValue(outbox.getPayload(), new TypeReference<Map<String, Object>>() {});
            Long orderId = Long.valueOf(payload.get("orderId").toString());

            logger.info("Payment success for orderId={}", orderId);

            // 【新增步骤 1】: 更新订单状态为 PAID
            // 将状态更新放在调用外部服务之前，如果更新失败，则不会继续
            try {
                orderService.updateOrderStatus(orderId, OrderStatus.PAID);
                logger.info("Order status updated to PAID for orderId={}", orderId);
            } catch (Exception e) {
                logger.error("Failed to update order status to PAID for orderId={}. Will retry. Error: {}", orderId, e.getMessage(), e);
                return false; // 更新订单状态是关键步骤，失败了必须重试
            }

            // --- 后续逻辑基本保持不变 ---

            logger.info("Triggering delivery request for orderId={}", orderId);

            // 1. 查询订单信息
            Order order = orderRepository.findByIdWithProduct(orderId) // 使用 findByIdWithProduct 保证商品信息被加载
                    .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

            // 2. 查询用户信息 (逻辑保持不变)
            Account account = accountRepository.findById(order.getUserId()).orElse(null);
            String email = (account != null) ? account.getEmail() : "customer@example.com";
            String userName = (account != null) ? account.getFirstName() + " " + account.getLastName() : "Customer";

            // 3. 【修改】获取订单商品信息
            // 因为 Order 已经和 Product 直接关联，不再需要 getOrderItems()
            if (order.getProduct() == null) {
                throw new IllegalStateException("Order " + orderId + " has no associated product.");
            }
            String productName = order.getProduct().getName();
            int quantity = order.getQuantity();

            // 4. 构造配送请求 (逻辑保持不变)
            DeliveryRequestDTO deliveryRequest = new DeliveryRequestDTO();
            deliveryRequest.setOrderId(orderId.toString());
            deliveryRequest.setEmail(email);
            deliveryRequest.setUserName(userName);
            deliveryRequest.setToAddress("Default Address - 123 Main St");
            deliveryRequest.setFromAddress(Collections.singletonList("Warehouse-1, 456 Storage Rd"));
            deliveryRequest.setProductName(productName);
            deliveryRequest.setQuantity(quantity);
            deliveryRequest.setNotificationUrl("http://localhost:8082/api/delivery-webhook");

            // 5. 【优化】确认库存预留 (Saga模式中的"Confirm")
            // 这一步的目的是将 HOLD 状态的库存事务标记为 COMMITTED/CONFIRMED
            // 这通常由 WarehouseService 提供一个专门的方法来完成
            // warehouseService.confirmHold(order.getInventoryTransactionIds());
            // 如果你的流程中没有这一步，可以直接跳过或移除，但保留它体现了更完整的Saga思想
            logger.info("Confirming stock hold for orderId={}", orderId);

            // 6. 调用DeliveryService (逻辑保持不变)
            DeliveryResponseDTO response = deliveryAdapter.createDelivery(deliveryRequest);

            if (response.isSuccess()) {
                logger.info("Delivery request created successfully: orderId={}, deliveryId={}", orderId, response.getDeliveryId());

                try {
                    // 再次获取最新的 order 对象
                    Order orderToUpdate = orderRepository.findById(orderId)
                            .orElseThrow(() -> new RuntimeException("Order disappeared unexpectedly: " + orderId));

                    // 设置并保存 deliveryId
                    orderToUpdate.setDeliveryId(response.getDeliveryId());
                    orderRepository.save(orderToUpdate);

                    logger.info("Saved deliveryId {} to orderId {}", response.getDeliveryId(), orderId);

                } catch (Exception e) {
                    logger.error("CRITICAL: Failed to save deliveryId for orderId {}. This will break delivery notifications!",
                            orderId, e);
                    // 即使保存deliveryId失败，配送任务也已经创建了，所以不应该返回false让整个流程重试
                    // 这是一个需要人工介入修复的脏数据
                }
                return true;
            } else {
//                logger.error("Delivery request failed: orderId={}, message={}", orderId, response.getMessage());
//                // 如果配送创建失败，应该考虑触发一个补偿流程（比如退款）
//                return false;
                logger.error("CRITICAL: Delivery creation failed permanently for orderId {}. Triggering compensation.", orderId);

                // 【高级实现】: 创建一个新的 Outbox 事件，来触发退款
                try {
                    // 你需要一个 OutboxService 来辅助创建事件
                    outboxService.createDeliveryFailedEvent(orderId, response.getMessage());
                } catch (Exception e) {
                    logger.error("FATAL: Failed to even create the compensation event for orderId {}.", orderId, e);
                    // 如果连创建补偿事件都失败了，就需要人工介入了
                }

                // 即使触发了补偿，我们仍然返回 true，因为这个 PAYMENt_SUCCESS 事件本身已经被“处理”了
                // （它的后续处理是触发了另一个事件）
                return true;
            }

        } catch (Exception e) {
            logger.error("Failed to process PAYMENT_SUCCESS for outboxId={}: {}", outbox.getId(), e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 处理支付失败事件 - 释放预留库存 + 发送Email通知
     */
    private boolean processPaymentFailed(PaymentOutbox outbox) {
        try {
            Map<String, Object> payload = objectMapper.readValue(outbox.getPayload(), new TypeReference<Map<String, Object>>() {});
            Long orderId = Long.valueOf(payload.get("orderId").toString());
            String error = payload.get("error").toString();

            logger.info("Payment failed for orderId={}, error={}", orderId, error);

            Order order = orderRepository.findById(orderId).orElse(null);
            if (order == null) {
                logger.warn("Order not found for failed payment, cannot release stock. orderId={}", orderId);
                return true; // 订单都没了，也就不需要释放库存了，认为处理成功
            }

            // 1. 释放库存预留
            try {
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
            } catch (Exception e) {
                logger.error("Failed to release inventory for orderId={}. Will retry. Error: {}", orderId, e.getMessage(), e);
                return false; // 释放库存是关键步骤，失败了必须重试
            }

            // 2. 发送失败通知邮件
            try {
                accountRepository.findById(order.getUserId()).ifPresent(acc -> {
                    if (acc.getEmail() != null) {
                        emailAdapter.sendOrderFailed(acc.getEmail(), String.valueOf(orderId), error);
                    }
                });
            } catch (Exception e) {
                logger.warn("Failed to send order failure email for orderId={}: {}", orderId, e.getMessage());
                // 邮件发送失败不影响主流程，不返回false
            }

            return true;

        } catch (Exception e) {
            logger.error("Failed to process PAYMENT_FAILED for outboxId={}: {}", outbox.getId(), e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 处理退款成功事件 - 发送Email通知
     */
    private boolean processRefundSuccess(PaymentOutbox outbox) {
        try {
            Map<String, Object> payload = objectMapper.readValue(
                    outbox.getPayload(), new TypeReference<Map<String, Object>>() {});
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

            return true;
            
        } catch (Exception e) {
            logger.error("Failed to process REFUND_SUCCESS: {}", e.getMessage(), e);
            return false;
        }
    }

    private boolean processDeliveryFailed(PaymentOutbox outbox) {
        try {
            Long orderId = outbox.getOrderId();
            String reason = "Delivery service failed to create shipment.";

            // 调用 PaymentService 的退款方法
            paymentService.refundPayment(orderId, reason);

            // 将订单状态更新为一个特殊的失败状态
            orderService.updateOrderStatus(orderId, OrderStatus.CANCELLED_SYSTEM);

            return true;
        } catch (Exception e) {
            logger.error("FATAL: Compensation (refund) failed for orderId {}. Needs manual intervention.", outbox.getOrderId(), e);
            return false; // 让这个补偿操作也进行重试
        }
    }
}


