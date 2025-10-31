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
            
            // 1. 释放库存预留（调用 WarehouseService.unholdProduct）✅
            boolean reservationReleased = false;
            Order order = null;
            try {
                // 查询订单获取保存的库存事务ID
                order = orderRepository.findById(orderId).orElse(null);
                if (order != null && order.getInventoryTransactionIds() != null && !order.getInventoryTransactionIds().isEmpty()) {
                    // 解析逗号分隔的事务ID列表
                    List<Long> txIds = java.util.Arrays.stream(order.getInventoryTransactionIds().split(","))
                            .filter(s -> s != null && !s.trim().isEmpty())
                            .map(Long::valueOf)
                            .collect(java.util.stream.Collectors.toList());
                    
                    if (!txIds.isEmpty()) {
                        UnholdProductRequest unholdRequest = new UnholdProductRequest();
                        unholdRequest.setInventoryTransactionIds(txIds);
                        reservationReleased = warehouseService.unholdProduct(unholdRequest);
                        logger.info("Released inventory reservations for failed payment, orderId={}, txIds={}", 
                                orderId, txIds);
                    } else {
                        logger.warn("No inventory transaction IDs found for orderId={}", orderId);
                        reservationReleased = true; // 没有事务ID也算成功（可能是旧订单）
                    }
                } else {
                    logger.warn("Order not found or no inventory transaction IDs for orderId={}", orderId);
                    reservationReleased = true; // 没有事务ID也算成功（可能是旧订单或测试数据）
                }
            } catch (Exception e) {
                logger.error("Failed to release inventory reservations for orderId={}: {}", orderId, e.getMessage(), e);
                // 预留释放失败，应该重试
                return false;
            }
            
            // 2. 发送失败通知邮件
            try {
                if (order == null) {
                    order = orderRepository.findById(orderId).orElse(null);
                }
                String email = null;
                if (order != null) {
                    var acc = accountRepository.findById(order.getUserId()).orElse(null);
                    if (acc != null) email = acc.getEmail();
                }
                if (email != null) {
                    emailAdapter.sendOrderFailed(email, String.valueOf(orderId), error);
                }
            } catch (Exception e) {
                logger.warn("Send order failed email error: {}", e.getMessage());
            }
            
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
}


