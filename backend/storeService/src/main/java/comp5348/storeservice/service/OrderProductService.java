package comp5348.storeservice.service;

import comp5348.storeservice.adapter.BankAdapter;
import comp5348.storeservice.dto.*;
import comp5348.storeservice.model.*;
import comp5348.storeservice.repository.OrderRepository;
import comp5348.storeservice.repository.ProductRepository;
import comp5348.storeservice.service.WarehouseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Integrated Order and Product Management Service
 * Integrates product management, order management, and payment functionality
 */
@Service
@Transactional
public class OrderProductService {
    
    private static final Logger logger = LoggerFactory.getLogger(OrderProductService.class);
    
    @Autowired
    private ProductService productService;
    
    @Autowired
    private OrderService orderService;
    
    @Autowired
    private PaymentService paymentService;
    
    @Autowired
    private ProductRepository productRepository;
    
    @Autowired
    private OrderRepository orderRepository;
    
    @Autowired
    private BankAdapter bankAdapter;

    @Autowired
    private WarehouseService warehouseService;
    
    @org.springframework.beans.factory.annotation.Value("${bank.customer.account:CUSTOMER_ACCOUNT_001}")
    private String customerAccount;

    @Autowired
    private comp5348.storeservice.repository.AccountRepository accountRepository;
    
    /**
     * Create order and process payment
     * This is the core method of integration, handling the entire flow from product selection to payment completion
     */
    public OrderDTO createOrderWithPayment(CreateOrderRequest request) {
        logger.info("Creating order with payment for user: {}", request.getUserId());
        
        // 0. Estimate total price
        java.math.BigDecimal estimatedTotal = java.math.BigDecimal.ZERO;
        for (CreateOrderRequest.OrderItemRequest item : request.getOrderItems()) {
            comp5348.storeservice.model.Product p = productRepository.findById(item.getProductId())
                    .orElseThrow(() -> new RuntimeException("Product not found: " + item.getProductId()));
            int qty = (item.getQuantity() == null || item.getQuantity() <= 0) ? 1 : item.getQuantity();
            estimatedTotal = estimatedTotal.add(p.getPrice().multiply(java.math.BigDecimal.valueOf(qty)));
        }

        // 0.1 Only allow user personal accounts to place orders; if no account or insufficient balance, reject directly
        var userAcc = accountRepository.findById(request.getUserId()).orElse(null);
        String fromAccount;
        if (userAcc == null) {
            throw new IllegalStateException("User not found: " + request.getUserId());
        }
        if (userAcc.getBankAccountNumber() == null || userAcc.getBankAccountNumber().isEmpty()) {
            // Fall back to global default customer account for local testing; production should require binding
            logger.warn("User {} has no linked bank account. Falling back to default customer account {}.", request.getUserId(), customerAccount);
            fromAccount = customerAccount;
        } else {
            fromAccount = userAcc.getBankAccountNumber();
        }

        java.math.BigDecimal balance = bankAdapter.getBalance(fromAccount);
        if (balance == null) {
            throw new IllegalStateException("Bank service unavailable for account balance check");
        }
        if (balance.compareTo(estimatedTotal) < 0) {
            throw new IllegalArgumentException("Insufficient balance. Available: " + balance + ", Required: " + estimatedTotal);
        }

        // 1. Create order
        OrderDTO order = orderService.createOrder(request);
        
        // 2. Update order status to pending payment
        orderService.updateOrderStatus(order.getId(), OrderStatus.PENDING_PAYMENT);
        
        // 3. Create payment record
        try {
            PaymentRequest paymentRequest = new PaymentRequest();
            paymentRequest.setOrderId(order.getId());
            paymentRequest.setAmount(order.getTotalAmount());
            
            Payment payment = paymentService.createPayment(paymentRequest.getOrderId(), paymentRequest.getAmount());
            logger.info("Payment created for order: {}, paymentId: {}", order.getId(), payment.getId());
            
        } catch (Exception e) {
            logger.error("Failed to create payment for order {}: {}", order.getId(), e.getMessage());
            // If payment creation fails, cancel order
            orderService.cancelOrder(order.getId());
            throw new RuntimeException("Failed to create payment: " + e.getMessage());
        }
        
        return order;
    }
    
    /**
     * Handle order status update after successful payment
     */
    public OrderDTO processPaymentSuccess(Long orderId) {
        logger.info("Processing payment success for order: {}", orderId);
        
        // 1. Update order status to paid
        OrderDTO order = orderService.updateOrderStatus(orderId, OrderStatus.PAID);
        
        // 2. Update order status to processing
        order = orderService.updateOrderStatus(orderId, OrderStatus.PROCESSING);
        
        logger.info("Order {} status updated to PROCESSING after successful payment", orderId);
        
        return order;
    }
    
    /**
     * Handle order status update after payment failure
     */
    public OrderDTO processPaymentFailure(Long orderId) {
        logger.info("Processing payment failure for order: {}", orderId);
        
        // Cancel order
        OrderDTO order = orderService.cancelOrder(orderId);
        
        logger.info("Order {} cancelled due to payment failure", orderId);
        
        return order;
    }
    
    /**
     * Get complete order information (including payment status)
     */
    public OrderWithPaymentDTO getOrderWithPaymentInfo(Long orderId) {
        logger.info("Getting order with payment info for order: {}", orderId);
        
        OrderDTO order = orderService.getOrderById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));
        
        // Get payment information
        Optional<Payment> payment = paymentService.getPaymentByOrderId(orderId);
        
        OrderWithPaymentDTO orderWithPayment = new OrderWithPaymentDTO();
        orderWithPayment.setOrder(order);
        orderWithPayment.setPayment(payment.orElse(null));
        
        return orderWithPayment;
    }
    
    /**
     * Get user's order list (including payment status)
     */
    public List<OrderWithPaymentDTO> getUserOrdersWithPaymentInfo(Long userId) {
        logger.info("Getting user orders with payment info for user: {}", userId);
        
        List<OrderDTO> orders = orderService.getOrdersByUserId(userId);
        
        return orders.stream()
                .map(order -> {
                    Optional<Payment> payment = paymentService.getPaymentByOrderId(order.getId());
                    OrderWithPaymentDTO orderWithPayment = new OrderWithPaymentDTO();
                    orderWithPayment.setOrder(order);
                    orderWithPayment.setPayment(payment.orElse(null));
                    return orderWithPayment;
                })
                .collect(Collectors.toList());
    }
    
    /**
     * Cancel order and process refund
     */
    public OrderDTO cancelOrderWithRefund(Long orderId, String reason) {
        logger.info("Cancelling order with refund for order: {}, reason: {}", orderId, reason);
        
        // 1. Check order status
        OrderDTO order = orderService.getOrderById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));
        
        if (order.getStatus().equals(OrderStatus.CANCELLED.name())) {
            throw new RuntimeException("Order is already cancelled");
        }
        
        // 2. Cancel order
        order = orderService.cancelOrder(orderId);
        
        // 3. If paid, process refund
        Optional<Payment> payment = paymentService.getPaymentByOrderId(orderId);
        if (payment.isPresent() && payment.get().getStatus() == PaymentStatus.SUCCESS) {
            try {
                paymentService.refundPayment(orderId, reason);
                logger.info("Refund processed for order: {}", orderId);
            } catch (Exception e) {
                logger.error("Failed to process refund for order {}: {}", orderId, e.getMessage());
                throw new RuntimeException("Failed to process refund: " + e.getMessage());
            }
        }
        
        return order;
    }
    
    /**
     * Get product stock information (using multi-warehouse aggregated inventory)
     */
    public ProductStockDTO getProductStockInfo(Long productId) {
        logger.info("Getting product stock info for product: {}", productId);
        
        ProductDTO product = productService.getProductById(productId)
                .orElseThrow(() -> new RuntimeException("Product not found: " + productId));
        
        // Use multi-warehouse aggregated inventory
        int totalQuantity = warehouseService.getProductQuantity(productId);
        
        ProductStockDTO stockInfo = new ProductStockDTO();
        stockInfo.setProductId(productId);
        stockInfo.setProductName(product.getName());
        stockInfo.setCurrentStock(totalQuantity);
        stockInfo.setAvailable(totalQuantity > 0);
        
        return stockInfo;
    }
    
    
}
