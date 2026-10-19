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

    @Autowired
    private comp5348.storeservice.repository.PaymentRepository paymentRepository;

    @Autowired
    private OutboxService outboxService;

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

        // 1. Create order (stock will be reserved during order creation)
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

    /**
     * Pay Now on an unpaid order (rule 8). Never calls bank: it only asks PaymentService to queue a new attempt,
     * and only if the last one definitely failed. The page then shows "payment processing" and polls the status.
     */
    public OrderDTO retryOrderPayment(Long orderId, String userEmail) {
        logger.info("Pay Now for order: {}, user: {}", orderId, userEmail);

        OrderDTO order = orderService.getOrderById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

        Account userAcc = accountRepository.findByEmail(userEmail)
                .orElseThrow(() -> new IllegalStateException("User account not found for email: " + userEmail));
        // The charge always comes from the order owner's bank account, so only the owner may trigger it
        if (!userAcc.getId().equals(order.getUserId())) {
            throw new IllegalStateException("Order " + orderId + " does not belong to the current user");
        }

        if (OrderStatus.PAID.name().equals(order.getStatus())) {
            return order;
        }
        if (!OrderStatus.PENDING_PAYMENT.name().equals(order.getStatus())) {
            throw new IllegalStateException("Order is not awaiting payment. Current status: " + order.getStatus());
        }

        // OutboxProcessor charges the bank account stored here; look it up once if it is missing (no money moves)
        if (userAcc.getBankAccountNumber() == null || userAcc.getBankAccountNumber().isEmpty()) {
            String bankAccountNumber = bankAdapter.getAccountByOwnerEmail(userEmail);
            if (bankAccountNumber == null || bankAccountNumber.isEmpty()) {
                throw new IllegalStateException("User has no linked bank account. Please go to 'Add Money' page to create your bank account first.");
            }
            userAcc.setBankAccountNumber(bankAccountNumber);
            accountRepository.save(userAcc);
        }

        PaymentService.PayNowResult result = paymentService.payNow(orderId);
        logger.info("Pay Now for order {}: {}", orderId, result);
        return orderService.getOrderById(orderId).orElse(order);
    }


}
